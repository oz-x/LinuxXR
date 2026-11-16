package com.linuxxr.vnc

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal RFB 3.7/3.8 client for a local TigerVNC Xvnc reached over a private Unix socket.
 * Security "None" only (the socket lives in the app's private data dir).
 * Encodings: Raw, CopyRect, DesktopSize, ExtendedDesktopSize.
 *
 * Framebuffer pixels are stored as R,G,B,X bytes (row-major, stride = width * 4) so the
 * renderer can upload them directly as GL_RGBA.
 */
class RfbClient(input: InputStream, private val output: OutputStream, private val listener: Listener) {

    interface Listener {
        fun onResize(width: Int, height: Int) {}
        fun onUpdate() {}
        fun onBell() {}
        fun onServerCutText(text: String) {}
    }

    private val inp = DataInputStream(input.buffered(1 shl 16))

    /** Guards [framebuffer], [width], [height] and the dirty region. */
    val lock = Any()
    var framebuffer = ByteArray(0); private set
    var width = 0; private set
    var height = 0; private set
    var desktopName = ""; private set

    private var dirtyX0 = Int.MAX_VALUE
    private var dirtyY0 = Int.MAX_VALUE
    private var dirtyX1 = 0
    private var dirtyY1 = 0

    private var screenId = 0L
    private var supportsSetDesktopSize = false
    private var scratch = ByteArray(0)

    fun handshake() {
        val version = ByteArray(12).also { inp.readFully(it) }.toString(Charsets.US_ASCII)
        if (!version.startsWith("RFB 003.")) throw IOException("Not an RFB server: $version")
        val minor = version.substring(8, 11).toInt()
        val useMinor = if (minor >= 8) 8 else if (minor == 7) 7 else 3
        write("RFB 003.00$useMinor\n".toByteArray(Charsets.US_ASCII))

        if (useMinor == 3) {
            val type = inp.readInt()
            if (type == 0) throw IOException("Server refused: ${readString()}")
            if (type != SEC_NONE) throw IOException("Unsupported security type $type")
        } else {
            val count = inp.readUnsignedByte()
            if (count == 0) throw IOException("Server refused: ${readString()}")
            val types = ByteArray(count).also { inp.readFully(it) }
            if (types.none { it.toInt() == SEC_NONE }) {
                throw IOException("Server requires unsupported security types ${types.toList()}")
            }
            write(byteArrayOf(SEC_NONE.toByte()))
            if (useMinor == 8) {
                if (inp.readInt() != 0) throw IOException("Security failed: ${readString()}")
            }
        }

        write(byteArrayOf(1)) // ClientInit: shared
        val w = inp.readUnsignedShort()
        val h = inp.readUnsignedShort()
        inp.readFully(ByteArray(16)) // server pixel format; we override it below
        desktopName = readString()
        resize(w, h)

        // 32bpp, depth 24, little-endian, true colour, R at byte 0, G at 1, B at 2.
        write(ByteBuffer.allocate(20).apply {
            put(0); put(0); put(0); put(0)
            put(32); put(24); put(0); put(1)
            putShort(255); putShort(255); putShort(255)
            put(0); put(8); put(16)
            put(0); put(0); put(0)
        }.array())

        val encodings = intArrayOf(ENC_COPY_RECT, ENC_RAW, ENC_EXT_DESKTOP_SIZE, ENC_DESKTOP_SIZE)
        write(ByteBuffer.allocate(4 + 4 * encodings.size).apply {
            put(2); put(0); putShort(encodings.size.toShort())
            encodings.forEach { putInt(it) }
        }.array())
        requestUpdate(incremental = false)
    }

    /** Reads server messages until the stream ends. Call after [handshake]. */
    fun run() {
        while (true) {
            val type = try {
                inp.readUnsignedByte()
            } catch (e: EOFException) {
                return
            }
            when (type) {
                0 -> readFramebufferUpdate()
                1 -> { // SetColourMapEntries (unused with true colour)
                    inp.readUnsignedByte(); inp.readUnsignedShort()
                    inp.readFully(ByteArray(inp.readUnsignedShort() * 6))
                }
                2 -> listener.onBell()
                3 -> {
                    inp.readFully(ByteArray(3))
                    val text = ByteArray(inp.readInt()).also { inp.readFully(it) }
                    listener.onServerCutText(String(text, Charsets.ISO_8859_1))
                }
                else -> throw IOException("Unknown server message $type")
            }
        }
    }

    private fun readFramebufferUpdate() {
        inp.readUnsignedByte()
        val rects = inp.readUnsignedShort()
        repeat(rects) {
            val x = inp.readUnsignedShort()
            val y = inp.readUnsignedShort()
            val w = inp.readUnsignedShort()
            val h = inp.readUnsignedShort()
            when (val enc = inp.readInt()) {
                ENC_RAW -> readRaw(x, y, w, h)
                ENC_COPY_RECT -> copyRect(inp.readUnsignedShort(), inp.readUnsignedShort(), x, y, w, h)
                ENC_DESKTOP_SIZE -> resize(w, h)
                ENC_EXT_DESKTOP_SIZE -> {
                    val screens = inp.readUnsignedByte()
                    inp.readFully(ByteArray(3))
                    repeat(screens) { i ->
                        val id = inp.readInt().toLong() and 0xffffffffL
                        inp.readFully(ByteArray(12)) // x, y, w, h, flags
                        if (i == 0) screenId = id
                    }
                    supportsSetDesktopSize = true
                    // y carries the status of a client-requested resize; non-zero means rejected.
                    if (y == 0) resize(w, h)
                }
                else -> throw IOException("Unexpected encoding $enc")
            }
        }
        listener.onUpdate()
        requestUpdate(incremental = true)
    }

    private fun readRaw(x: Int, y: Int, w: Int, h: Int) {
        val rowBytes = w * 4
        val total = rowBytes * h
        if (scratch.size < total) scratch = ByteArray(total)
        inp.readFully(scratch, 0, total)
        synchronized(lock) {
            if (x + w > width || y + h > height) return
            val stride = width * 4
            for (row in 0 until h) {
                System.arraycopy(scratch, row * rowBytes, framebuffer, (y + row) * stride + x * 4, rowBytes)
            }
            markDirty(x, y, w, h)
        }
    }

    private fun copyRect(srcX: Int, srcY: Int, x: Int, y: Int, w: Int, h: Int) {
        synchronized(lock) {
            if (x + w > width || y + h > height || srcX + w > width || srcY + h > height) return
            val stride = width * 4
            val rowBytes = w * 4
            // Copy in the direction that never overwrites rows not yet copied.
            val rows = if (srcY < y) (h - 1 downTo 0) else (0 until h)
            for (row in rows) {
                System.arraycopy(framebuffer, (srcY + row) * stride + srcX * 4, framebuffer, (y + row) * stride + x * 4, rowBytes)
            }
            markDirty(x, y, w, h)
        }
    }

    private fun resize(w: Int, h: Int) {
        synchronized(lock) {
            if (w == width && h == height) return@synchronized
            width = w
            height = h
            framebuffer = ByteArray(w * h * 4)
            markDirty(0, 0, w, h)
        }
        listener.onResize(w, h)
    }

    private fun markDirty(x: Int, y: Int, w: Int, h: Int) {
        dirtyX0 = minOf(dirtyX0, x)
        dirtyY0 = minOf(dirtyY0, y)
        dirtyX1 = maxOf(dirtyX1, x + w)
        dirtyY1 = maxOf(dirtyY1, y + h)
    }

    /** Must be called with [lock] held. */
    fun takeDirty(): IntArray? {
        if (dirtyX0 >= dirtyX1 || dirtyY0 >= dirtyY1) return null
        val r = intArrayOf(dirtyX0, dirtyY0, dirtyX1 - dirtyX0, dirtyY1 - dirtyY0)
        dirtyX0 = Int.MAX_VALUE; dirtyY0 = Int.MAX_VALUE; dirtyX1 = 0; dirtyY1 = 0
        return r
    }

    fun requestUpdate(incremental: Boolean) {
        val w: Int
        val h: Int
        synchronized(lock) { w = width; h = height }
        write(ByteBuffer.allocate(10).apply {
            put(3); put(if (incremental) 1 else 0)
            putShort(0); putShort(0); putShort(w.toShort()); putShort(h.toShort())
        }.array())
    }

    fun sendKey(keysym: Int, down: Boolean) {
        write(ByteBuffer.allocate(8).apply {
            put(4); put(if (down) 1 else 0); putShort(0); putInt(keysym)
        }.array())
    }

    /** [buttons] is the RFB button mask: bit0 left, bit1 middle, bit2 right, bit3/4 wheel up/down. */
    fun sendPointer(buttons: Int, x: Int, y: Int) {
        write(ByteBuffer.allocate(6).apply {
            put(5); put(buttons.toByte())
            putShort(x.coerceIn(0, 0xffff).toShort()); putShort(y.coerceIn(0, 0xffff).toShort())
        }.array())
    }

    fun sendClientCutText(text: String) {
        val bytes = text.toByteArray(Charsets.ISO_8859_1)
        write(ByteBuffer.allocate(8 + bytes.size).apply {
            put(6); put(0); put(0); put(0); putInt(bytes.size); put(bytes)
        }.array())
    }

    /** Asks the server to resize the remote desktop. Returns false if the server can't. */
    fun requestDesktopSize(w: Int, h: Int): Boolean {
        if (!supportsSetDesktopSize || w <= 0 || h <= 0) return false
        write(ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN).apply {
            put(251.toByte()); put(0)
            putShort(w.toShort()); putShort(h.toShort())
            put(1); put(0)
            putInt(screenId.toInt()); putShort(0); putShort(0)
            putShort(w.toShort()); putShort(h.toShort()); putInt(0)
        }.array())
        return true
    }

    private fun readString(): String {
        val bytes = ByteArray(inp.readInt()).also { inp.readFully(it) }
        return String(bytes, Charsets.UTF_8)
    }

    private fun write(bytes: ByteArray) {
        synchronized(output) {
            output.write(bytes)
            output.flush()
        }
    }

    companion object {
        const val SEC_NONE = 1
        const val ENC_RAW = 0
        const val ENC_COPY_RECT = 1
        const val ENC_DESKTOP_SIZE = -223
        const val ENC_EXT_DESKTOP_SIZE = -308
    }
}
