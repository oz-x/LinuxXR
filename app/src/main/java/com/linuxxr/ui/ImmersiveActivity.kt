package com.linuxxr.ui

import android.app.Activity
import android.opengl.GLES30
import android.os.Bundle
import android.view.KeyEvent
import com.linuxxr.core.Guest
import com.linuxxr.core.SessionService
import com.linuxxr.core.SessionState
import com.linuxxr.vnc.DisplayConnection
import com.linuxxr.vnc.KeySyms
import com.linuxxr.vnc.RfbClient
import java.nio.ByteBuffer

/**
 * Immersive stereo VR view (OpenXR, native renderer in xr_main.cpp). All running displays are
 * shown as curved screens around the user. The native render thread calls [onFrame],
 * [onPointer] and [onScroll]; uploads run on that thread with its EGL context current.
 */
class ImmersiveActivity : Activity() {

    private val connections = mutableListOf<DisplayConnection>()
    private val textures = IntArray(MAX_DISPLAYS)
    private val texSize = Array(MAX_DISPLAYS) { IntArray(2) }
    @Volatile private var focusedDisplay = 0
    private var buttons = 0
    private var renderThread: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val count = (SessionService.state.value as? SessionState.Running)?.displays ?: 1
        val guest = Guest(this)
        for (n in 1..count.coerceAtMost(MAX_DISPLAYS)) {
            connections += DisplayConnection(guest.displaySocket(n), n, object : DisplayConnection.Listener {}).start()
        }
        renderThread = Thread({
            nativeRun()
            runOnUiThread { finish() }
        }, "linuxxr-xr").apply { start() }
    }

    override fun onDestroy() {
        nativeRequestExit()
        renderThread?.join(3000)
        connections.forEach { it.close() }
        super.onDestroy()
    }

    // ---- called from the native render thread ------------------------------------------------

    /** Uploads changed framebuffer regions; returns [textureId, width, height] per display. */
    @Suppress("unused")
    fun onFrame(): IntArray {
        val out = IntArray(connections.size * 3)
        connections.forEachIndexed { i, conn ->
            val c = conn.client
            if (c == null) {
                out[i * 3] = 0
                return@forEachIndexed
            }
            upload(i, c)
            out[i * 3] = textures[i]
            out[i * 3 + 1] = texSize[i][0]
            out[i * 3 + 2] = texSize[i][1]
        }
        return out
    }

    private fun upload(i: Int, c: RfbClient) {
        synchronized(c.lock) {
            val w = c.width
            val h = c.height
            if (w == 0 || h == 0) return
            if (textures[i] == 0) {
                val ids = IntArray(1)
                GLES30.glGenTextures(1, ids, 0)
                textures[i] = ids[0]
            }
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[i])
            val region = if (texSize[i][0] != w || texSize[i][1] != h) {
                // Immutable storage with a full mip chain: text stays legible at a distance.
                if (texSize[i][0] != 0) {
                    GLES30.glDeleteTextures(1, intArrayOf(textures[i]), 0)
                    val ids = IntArray(1)
                    GLES30.glGenTextures(1, ids, 0)
                    textures[i] = ids[0]
                    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[i])
                }
                val levels = 1 + (31 - Integer.numberOfLeadingZeros(maxOf(w, h)))
                GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, levels, GLES30.GL_SRGB8_ALPHA8, w, h)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
                GLES30.glTexParameterf(GLES30.GL_TEXTURE_2D, GL_TEXTURE_MAX_ANISOTROPY_EXT, 8f)
                texSize[i][0] = w
                texSize[i][1] = h
                c.takeDirty()
                intArrayOf(0, 0, w, h)
            } else {
                c.takeDirty() ?: return
            }
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, w)
            val offset = (region[1] * w + region[0]) * 4
            GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, region[0], region[1], region[2], region[3],
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, ByteBuffer.wrap(c.framebuffer, offset, c.framebuffer.size - offset))
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, 0)
            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        }
    }

    @Suppress("unused")
    fun onPointer(index: Int, u: Float, v: Float, mask: Int) {
        val c = connections.getOrNull(index)?.client ?: return
        focusedDisplay = index
        buttons = mask
        c.sendPointer(mask, (u * c.width).toInt(), (v * c.height).toInt())
    }

    @Suppress("unused")
    fun onScroll(index: Int, u: Float, v: Float, up: Boolean) {
        val c = connections.getOrNull(index)?.client ?: return
        val x = (u * c.width).toInt()
        val y = (v * c.height).toInt()
        c.sendPointer(buttons or (if (up) 8 else 16), x, y)
        c.sendPointer(buttons, x, y)
    }

    // ---- Bluetooth keyboard: typed into the display the controller last pointed at -------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val c = connections.getOrNull(focusedDisplay)?.client ?: return super.dispatchKeyEvent(event)
        val sym = KeySyms.forEvent(event) ?: return super.dispatchKeyEvent(event)
        when (event.action) {
            KeyEvent.ACTION_DOWN -> c.sendKey(sym, true)
            KeyEvent.ACTION_UP -> c.sendKey(sym, false)
        }
        return true
    }

    private external fun nativeRun()
    private external fun nativeRequestExit()

    companion object {
        private const val MAX_DISPLAYS = 4
        private const val GL_TEXTURE_MAX_ANISOTROPY_EXT = 0x84FE

        init {
            System.loadLibrary("linuxxr_xr")
        }
    }
}
