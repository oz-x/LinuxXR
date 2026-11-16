package com.linuxxr.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.opengl.GLUtils
import android.opengl.GLES30
import android.os.Bundle
import android.view.KeyEvent
import com.linuxxr.BuildConfig
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
    private val tiles = Array(MAX_DISPLAYS) { Tiles() }
    @Volatile private var focusedDisplay = 0
    private var buttons = 0
    private var renderThread: Thread? = null
    @Volatile private var ringRequested = false
    private var altHeld = false
    private val keyboard = VrKeyboard()
    private var keyboardTex = 0
    private var keyboardUploaded = false

    /**
     * One GL texture per [TILE_W]-wide column of a display. These are only staging copies: the
     * native side blits changed tiles into OpenXR cylinder-layer swapchains, which the
     * compositor samples directly (no eye-buffer resampling, so text stays sharp).
     */
    private class Tiles {
        var width = 0
        var height = 0
        var ids = IntArray(0)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val count = (SessionService.state.value as? SessionState.Running)?.displays ?: 1
        val guest = Guest(this)
        for (n in 1..count.coerceAtMost(MAX_DISPLAYS)) {
            val conn = arrayOfNulls<DisplayConnection>(1)
            conn[0] = DisplayConnection(guest.displaySocket(n), n, object : DisplayConnection.Listener {
                override fun onConnected(client: RfbClient) { if (n == 1) ringRequested = false }
                override fun onUpdate() {
                    // Display :1 becomes the 360° desktop: RING_MONITORS monitors side by side
                    // that wrap all the way around the user.
                    val c = conn[0]?.client ?: return
                    if (n == 1 && !ringRequested && c.canSetDesktopSize) {
                        ringRequested = true
                        if (c.width != RING_MONITORS * MONITOR_W || c.height != MONITOR_H) {
                            c.requestDesktopLayout(RING_MONITORS * MONITOR_W, MONITOR_H,
                                List(RING_MONITORS) { intArrayOf(it * MONITOR_W, 0, MONITOR_W, MONITOR_H) })
                        }
                    }
                }
            }, transparentKey = ROOT_KEY)
            connections += conn[0]!!.start()
        }
        renderThread = Thread({
            nativeRun()
            runOnUiThread { finish() }
        }, "linuxxr-xr").apply { start() }
    }

    override fun onDestroy() {
        dictation.release()
        nativeRequestExit()
        renderThread?.join(3000)
        connections.forEach { it.close() }
        super.onDestroy()
    }

    // ---- called from the native render thread ------------------------------------------------

    /**
     * Uploads changed framebuffer regions. Returns
     * `[count, (width, height, tileW, tiles, dirtyMask, tex…)…]`; bit k of dirtyMask = tile k changed.
     * A display that is not connected reports width = height = tiles = 0.
     */
    @Suppress("unused")
    fun onFrame(): IntArray {
        val out = ArrayList<Int>(1 + connections.size * 10)
        out += connections.size
        connections.forEachIndexed { i, conn ->
            val c = conn.client
            val t = tiles[i]
            val dirty = if (c == null) -1 else upload(t, c)
            if (dirty < 0) {
                out += listOf(0, 0, TILE_W, 0, 0)
            } else {
                out += listOf(t.width, t.height, TILE_W, t.ids.size, dirty)
                t.ids.forEach { out += it }
            }
        }
        return out.toIntArray()
    }

    /** Returns the bitmask of tiles that changed, or -1 if the display has no frame yet. */
    private fun upload(t: Tiles, c: RfbClient): Int {
        var dirtyMask = 0
        synchronized(c.lock) {
            val w = c.width
            val h = c.height
            if (w == 0 || h == 0) return -1
            val region = if (t.width != w || t.height != h) {
                if (t.ids.isNotEmpty()) GLES30.glDeleteTextures(t.ids.size, t.ids, 0)
                t.ids = IntArray((w + TILE_W - 1) / TILE_W)
                GLES30.glGenTextures(t.ids.size, t.ids, 0)
                t.ids.forEachIndexed { k, id ->
                    val tw = minOf(TILE_W, w - k * TILE_W)
                    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
                    GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_SRGB8_ALPHA8, tw, h)
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
                }
                t.width = w
                t.height = h
                c.takeDirty()
                intArrayOf(0, 0, w, h)
            } else {
                c.takeDirty() ?: return 0
            }
            val (rx, ry, rw, rh) = region
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, w)
            t.ids.forEachIndexed { k, id ->
                val tx0 = k * TILE_W
                val x0 = maxOf(rx, tx0)
                val x1 = minOf(rx + rw, tx0 + TILE_W, w)
                if (x0 >= x1) return@forEachIndexed
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
                val offset = (ry * w + x0) * 4
                GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, x0 - tx0, ry, x1 - x0, rh, GLES30.GL_RGBA,
                    GLES30.GL_UNSIGNED_BYTE, ByteBuffer.wrap(c.framebuffer, offset, c.framebuffer.size - offset))
                dirtyMask = dirtyMask or (1 shl k)
            }
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, 0)
        }
        return dirtyMask
    }

    /**
     * Pointer from the controller ray. [mask] uses RFB button bits plus [MOVE_WINDOW]: while set,
     * Alt is held so xfwm4 drags the window under the ray (grab with grip, anywhere in 360°).
     */
    @Suppress("unused")
    fun onPointer(index: Int, u: Float, v: Float, mask: Int) {
        val c = connections.getOrNull(index)?.client ?: return
        focusedDisplay = index
        val moving = mask and MOVE_WINDOW != 0
        if (moving != altHeld) {
            if (moving) c.sendKey(KeySyms.ALT_L, true)
            altHeld = moving
            if (!moving) {
                c.sendPointer(0, (u * c.width).toInt(), (v * c.height).toInt())
                c.sendKey(KeySyms.ALT_L, false)
            }
        }
        buttons = (mask and 0xff) or (if (moving) 1 else 0)
        c.sendPointer(buttons, (u * c.width).toInt(), (v * c.height).toInt())
    }

    // ---- virtual keyboard (quad layer) --------------------------------------------------------

    /** Returns `[texId, width, height, dirty]` for the keyboard bitmap, uploading it if it changed. */
    @Suppress("unused")
    fun onKeyboardFrame(): IntArray {
        val changed = keyboard.render()
        if (keyboardTex == 0) {
            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0)
            keyboardTex = ids[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, keyboardTex)
            GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_SRGB8_ALPHA8, VrKeyboard.WIDTH, VrKeyboard.HEIGHT)
        }
        val upload = changed || !keyboardUploaded
        if (upload) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, keyboardTex)
            GLUtils.texSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, keyboard.bitmap)
            keyboardUploaded = true
        }
        return intArrayOf(keyboardTex, VrKeyboard.WIDTH, VrKeyboard.HEIGHT, if (upload) 1 else 0)
    }

    /** Ray on the keyboard; returns VrKeyboard.pointer flags (1 haptic, 2 close, 4 dictation). */
    @Suppress("unused")
    fun onKeyboardPointer(u: Float, v: Float, down: Boolean): Int {
        if (u < 0f) {
            keyboard.leave()
            return 0
        }
        val r = keyboard.pointer(u, v, down, connections.getOrNull(focusedDisplay)?.client)
        if (r and 4 != 0) runOnUiThread { toggleDictation() }
        return r and 3
    }

    // ---- dictation: types into the display the controller last pointed at -------------------

    private fun toggleDictation() {
        if (dictation.active) {
            dictation.stop()
            keyboard.dictating = false
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            keyboard.status = "Falta permiso de micrófono: concédelo en el panel de LinuxXR"
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        keyboard.dictating = true
        dictation.start()
    }

    private val dictation: Dictation by lazy {
        Dictation(this, onStatus = { keyboard.status = it; keyboard.dictating = dictation.active }, onText = { typeText("$it ") })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Debug hook (adb): am start -n com.linuxxr/.ui.ImmersiveActivity --ez toggle_dictation true
        if (BuildConfig.DEBUG && intent.getBooleanExtra("toggle_dictation", false)) toggleDictation()
    }

    private fun typeText(text: String) {
        val c = connections.getOrNull(focusedDisplay)?.client ?: return
        text.codePoints().forEach {
            val sym = KeySyms.forCodePoint(it)
            c.sendKey(sym, true)
            c.sendKey(sym, false)
        }
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
        /** X root window colour that VR renders transparent (session.sh paints the root with it). */
        const val ROOT_KEY = 0x01FE02
        // 360° ring at ~24 px per degree, i.e. one desktop pixel per Quest 3 display pixel:
        // finer would be minified (shimmer), coarser would be magnified (blur).
        private const val RING_MONITORS = 6
        private const val MONITOR_W = 1440
        private const val MONITOR_H = 1200
        private const val TILE_W = MONITOR_W
        /** Extra pointer bit from native: grip held = move the window (Alt + drag). */
        private const val MOVE_WINDOW = 0x100

        init {
            System.loadLibrary("linuxxr_xr")
        }
    }
}
