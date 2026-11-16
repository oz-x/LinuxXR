package com.linuxxr.vnc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Drives RfbClient against a scripted RFB 3.8 server over pipes. */
class RfbClientTest {

    private val toClient = PipedOutputStream()
    private val clientIn = PipedInputStream(toClient, 1 shl 16)
    private val toServer = PipedOutputStream()
    private val serverIn = DataInputStream(PipedInputStream(toServer, 1 shl 16))
    private val server = DataOutputStream(toClient)

    private fun pixel(r: Int, g: Int, b: Int) = byteArrayOf(r.toByte(), g.toByte(), b.toByte(), 0)

    private fun startClient(listener: RfbClient.Listener): Pair<RfbClient, Thread> {
        val client = RfbClient(clientIn, toServer, listener)
        val t = Thread { client.handshake(); client.run() }.apply { isDaemon = true; start() }
        return client to t
    }

    private fun serverHandshake(width: Int, height: Int) {
        server.write("RFB 003.008\n".toByteArray()); server.flush()
        assertEquals("RFB 003.008\n", String(ByteArray(12).also { serverIn.readFully(it) }))
        server.write(byteArrayOf(2, 2, 1)); server.flush() // VncAuth, None
        assertEquals(1, serverIn.readUnsignedByte())
        server.writeInt(0); server.flush()
        assertEquals(1, serverIn.readUnsignedByte()) // shared
        server.writeShort(width); server.writeShort(height)
        server.write(ByteArray(16))
        server.writeInt(4); server.write("test".toByteArray()); server.flush()

        val setPixelFormat = ByteArray(20).also { serverIn.readFully(it) }
        assertEquals(0, setPixelFormat[0].toInt())
        assertEquals(32, setPixelFormat[4].toInt()) // bpp
        assertEquals(0, setPixelFormat[6].toInt())  // little-endian
        // red/green/blue shifts 0/8/16 => bytes R,G,B,X in memory
        assertArrayEquals(byteArrayOf(0, 8, 16), setPixelFormat.copyOfRange(14, 17))

        assertEquals(2, serverIn.readUnsignedByte())
        serverIn.readUnsignedByte()
        val n = serverIn.readUnsignedShort()
        val encodings = IntArray(n) { serverIn.readInt() }
        assertEquals(listOf(1, 0, -308, -223), encodings.toList())
        readUpdateRequest(incremental = false, width, height)
    }

    private fun readUpdateRequest(incremental: Boolean, w: Int, h: Int) {
        assertEquals(3, serverIn.readUnsignedByte())
        assertEquals(if (incremental) 1 else 0, serverIn.readUnsignedByte())
        assertEquals(listOf(0, 0, w, h), List(4) { serverIn.readUnsignedShort() })
    }

    private fun rectHeader(x: Int, y: Int, w: Int, h: Int, enc: Int) {
        server.writeShort(x); server.writeShort(y); server.writeShort(w); server.writeShort(h); server.writeInt(enc)
    }

    @Test
    fun rawCopyRectAndServerResize() {
        var updates = CountDownLatch(1)
        val resized = CountDownLatch(2) // initial ServerInit + ExtendedDesktopSize
        val (client, _) = startClient(object : RfbClient.Listener {
            override fun onUpdate() = updates.countDown()
            override fun onResize(width: Int, height: Int) = resized.countDown()
        })
        serverHandshake(4, 2)

        // Raw 2x2 at (1,0), then CopyRect of its top row to (0,1)..(1,1).
        server.writeByte(0); server.writeByte(0); server.writeShort(2)
        rectHeader(1, 0, 2, 2, RfbClient.ENC_RAW)
        server.write(pixel(1, 2, 3)); server.write(pixel(4, 5, 6))
        server.write(pixel(7, 8, 9)); server.write(pixel(10, 11, 12))
        rectHeader(0, 1, 2, 1, RfbClient.ENC_COPY_RECT)
        server.writeShort(1); server.writeShort(0)
        server.flush()
        readUpdateRequest(incremental = true, 4, 2)
        assert(updates.await(5, TimeUnit.SECONDS))

        synchronized(client.lock) {
            val fb = client.framebuffer
            fun px(x: Int, y: Int) = fb.copyOfRange((y * 4 + x) * 4, (y * 4 + x) * 4 + 3).map { it.toInt() }
            assertEquals(listOf(1, 2, 3), px(1, 0))
            assertEquals(listOf(4, 5, 6), px(2, 0))
            assertEquals(listOf(1, 2, 3), px(0, 1)) // copied
            assertEquals(listOf(4, 5, 6), px(1, 1)) // copied over the raw pixel
            assertEquals(listOf(10, 11, 12), px(2, 1))
            assertArrayEquals(intArrayOf(0, 0, 4, 2), client.takeDirty())
            assertEquals(null, client.takeDirty())
        }

        // Server-initiated ExtendedDesktopSize -> 8x6, screen id 7; then a client resize request uses it.
        updates = CountDownLatch(1)
        server.writeByte(0); server.writeByte(0); server.writeShort(1)
        rectHeader(0, 0, 8, 6, RfbClient.ENC_EXT_DESKTOP_SIZE)
        server.writeByte(1); server.write(ByteArray(3))
        server.writeInt(7); server.write(ByteArray(12))
        server.flush()
        readUpdateRequest(incremental = true, 8, 6)
        assert(resized.await(5, TimeUnit.SECONDS))
        assertEquals(8, client.width)
        assertEquals(6, client.height)

        assert(client.requestDesktopSize(1280, 720))
        assertEquals(251, serverIn.readUnsignedByte())
        serverIn.readUnsignedByte()
        assertEquals(1280, serverIn.readUnsignedShort())
        assertEquals(720, serverIn.readUnsignedShort())
        assertEquals(1, serverIn.readUnsignedByte())
        serverIn.readUnsignedByte()
        assertEquals(7, serverIn.readInt())
        assertEquals(listOf(0, 0, 1280, 720), List(4) { serverIn.readUnsignedShort() })
        assertEquals(0, serverIn.readInt()) // flags

        client.sendPointer(4, 10, 20)
        assertArrayEquals(byteArrayOf(5, 4, 0, 10, 0, 20), ByteArray(6).also { serverIn.readFully(it) })
        client.sendKey(KeySyms.forCodePoint('ñ'.code), true)
        assertArrayEquals(byteArrayOf(4, 1, 0, 0, 0, 0, 0, 0xf1.toByte()), ByteArray(8).also { serverIn.readFully(it) })
    }
}
