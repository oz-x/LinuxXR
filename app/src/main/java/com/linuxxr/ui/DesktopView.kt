package com.linuxxr.ui

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.text.InputType
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.linuxxr.vnc.KeySyms
import com.linuxxr.vnc.RfbClient
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Shows one remote X display. The framebuffer is uploaded to a GL texture (only the dirty
 * region per frame) and drawn letterboxed; input is mapped back to framebuffer pixels.
 */
class DesktopView(context: Context) : GLSurfaceView(context) {

    @Volatile var client: RfbClient? = null
        set(value) { field = value; requestRender() }

    /** Sticky modifiers from the on-screen key bar, released after the next key. */
    val stickyModifiers = linkedSetOf<Int>()
    var rightClickNext = false
    var onModifiersChanged: () -> Unit = {}

    private var buttons = 0
    private val renderer = Renderer()

    // Letterbox geometry, written by the GL thread, read by the UI thread.
    @Volatile private var viewport = intArrayOf(0, 0, 1, 1)
    @Volatile private var fbSize = intArrayOf(1, 1)

    init {
        setEGLContextClientVersion(3)
        // RGBA surface composited with alpha: key-coloured desktop pixels are alpha 0, so the
        // panel is see-through there (passthrough behind it in Horizon OS).
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        holder.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
        setZOrderOnTop(true)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        isFocusable = true
        isFocusableInTouchMode = true
    }

    // ---- input ----------------------------------------------------------------

    private fun toFb(x: Float, y: Float): Pair<Int, Int> {
        val v = viewport
        val f = fbSize
        val fx = ((x - v[0]) * f[0] / v[2]).toInt().coerceIn(0, f[0] - 1)
        // GL viewport origin is bottom-left; touch origin is top-left.
        val top = height - (v[1] + v[3])
        val fy = ((y - top) * f[1] / v[3]).toInt().coerceIn(0, f[1] - 1)
        return fx to fy
    }

    private fun pointer(mask: Int, x: Float, y: Float) {
        val c = client ?: return
        val (fx, fy) = toFb(x, y)
        c.sendPointer(mask, fx, fy)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) return handleMouse(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestFocus()
                buttons = if (rightClickNext) 4 else 1
                pointer(buttons, event.x, event.y)
            }
            MotionEvent.ACTION_MOVE -> pointer(buttons, event.x, event.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                buttons = 0
                pointer(0, event.x, event.y)
                if (rightClickNext) {
                    rightClickNext = false
                    onModifiersChanged()
                }
            }
        }
        return true
    }

    private fun handleMouse(event: MotionEvent): Boolean {
        var mask = 0
        if (event.buttonState and MotionEvent.BUTTON_PRIMARY != 0) mask = mask or 1
        if (event.buttonState and MotionEvent.BUTTON_TERTIARY != 0) mask = mask or 2
        if (event.buttonState and MotionEvent.BUTTON_SECONDARY != 0) mask = mask or 4
        if (event.actionMasked == MotionEvent.ACTION_DOWN) requestFocus()
        pointer(mask, event.x, event.y)
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            // Quest controller rays and mice hover over panels without pressing.
            MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_ENTER -> {
                pointer(buttons, event.x, event.y); return true
            }
            MotionEvent.ACTION_SCROLL -> {
                val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                val h = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
                if (v != 0f) wheel(if (v > 0) 8 else 16, event)
                if (h != 0f) wheel(if (h > 0) 64 else 32, event)
                return true
            }
            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> return handleMouse(event)
        }
        return super.onGenericMotionEvent(event)
    }

    private fun wheel(bit: Int, event: MotionEvent) {
        pointer(buttons or bit, event.x, event.y)
        pointer(buttons, event.x, event.y)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = key(event, true) || super.onKeyDown(keyCode, event)
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = key(event, false) || super.onKeyUp(keyCode, event)

    private fun key(event: KeyEvent, down: Boolean): Boolean {
        val c = client ?: return false
        val sym = KeySyms.forEvent(event) ?: return false
        if (down) pressWithModifiers(c, sym, releaseNow = false) else c.sendKey(sym, false)
        return true
    }

    /** Sends [sym] wrapped in any sticky modifiers from the key bar. */
    fun pressWithModifiers(c: RfbClient, sym: Int, releaseNow: Boolean = true) {
        val mods = stickyModifiers.toList()
        mods.forEach { c.sendKey(it, true) }
        c.sendKey(sym, true)
        if (releaseNow) c.sendKey(sym, false)
        if (mods.isNotEmpty()) {
            mods.asReversed().forEach { c.sendKey(it, false) }
            stickyModifiers.clear()
            onModifiersChanged()
        }
    }

    fun sendKeysym(sym: Int) {
        client?.let { pressWithModifiers(it, sym) }
    }

    fun typeText(text: CharSequence) {
        val c = client ?: return
        text.codePoints().forEach { pressWithModifiers(c, KeySyms.forCodePoint(it)) }
    }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        return object : BaseInputConnection(this, false) {
            private var composing = ""

            private fun replaceComposing(new: String) {
                // Keystroke-level diff: erase what differs, then type the new tail.
                val common = composing.commonPrefixWith(new).length
                repeat(composing.length - common) { sendKeysym(KeySyms.BACKSPACE) }
                typeText(new.substring(common))
                composing = new
            }

            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                replaceComposing(text.toString())
                composing = ""
                return true
            }

            override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
                replaceComposing(text.toString())
                return true
            }

            override fun finishComposingText(): Boolean {
                composing = ""
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength) { sendKeysym(KeySyms.BACKSPACE) }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) onKeyDown(event.keyCode, event)
                else if (event.action == KeyEvent.ACTION_UP) onKeyUp(event.keyCode, event)
                return true
            }
        }
    }

    // ---- rendering --------------------------------------------------------------

    private inner class Renderer : GLSurfaceView.Renderer {
        private var program = 0
        private var texture = 0
        private var texW = 0
        private var texH = 0
        private var surfaceW = 1
        private var surfaceH = 1
        private val quad = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            // x, y, u, v (triangle strip); v flipped so framebuffer row 0 is at the top.
            put(floatArrayOf(-1f, -1f, 0f, 1f, 1f, -1f, 1f, 1f, -1f, 1f, 0f, 0f, 1f, 1f, 1f, 0f)); position(0)
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            program = buildProgram()
            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0)
            texture = ids[0]
            texW = 0; texH = 0
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            surfaceW = width
            surfaceH = height
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES30.glViewport(0, 0, surfaceW, surfaceH)
            GLES30.glClearColor(0f, 0f, 0f, 0f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            val c = client ?: return
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
            synchronized(c.lock) {
                val w = c.width
                val h = c.height
                if (w == 0 || h == 0) return
                // A new texture (resize, or GL context recreated after onPause) needs the whole frame.
                val region = if (w != texW || h != texH) {
                    GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
                    texW = w; texH = h
                    fbSize = intArrayOf(w, h)
                    c.takeDirty()
                    intArrayOf(0, 0, w, h)
                } else {
                    c.takeDirty()
                }
                region?.let { (x, y, dw, dh) ->
                    GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, w)
                    val offset = (y * w + x) * 4
                    val buf = ByteBuffer.wrap(c.framebuffer, offset, c.framebuffer.size - offset)
                    GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, x, y, dw, dh, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
                    GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, 0)
                }
            }
            // Letterbox to keep pixels square.
            val scale = minOf(surfaceW.toFloat() / texW, surfaceH.toFloat() / texH)
            val vw = (texW * scale).toInt()
            val vh = (texH * scale).toInt()
            val vx = (surfaceW - vw) / 2
            val vy = (surfaceH - vh) / 2
            viewport = intArrayOf(vx, vy, vw, vh)
            GLES30.glViewport(vx, vy, vw, vh)
            GLES30.glUseProgram(program)
            quad.position(0)
            GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, quad)
            GLES30.glEnableVertexAttribArray(0)
            quad.position(2)
            GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, quad)
            GLES30.glEnableVertexAttribArray(1)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        }

        private fun buildProgram(): Int {
            val vs = compile(GLES30.GL_VERTEX_SHADER, """
                #version 300 es
                layout(location = 0) in vec2 aPos;
                layout(location = 1) in vec2 aUv;
                out vec2 vUv;
                void main() { vUv = aUv; gl_Position = vec4(aPos, 0.0, 1.0); }
            """.trimIndent())
            val fs = compile(GLES30.GL_FRAGMENT_SHADER, """
                #version 300 es
                precision mediump float;
                in vec2 vUv;
                uniform sampler2D uTex;
                out vec4 color;
                void main() { color = texture(uTex, vUv); }  // premultiplied: key pixels are (0,0,0,0)
            """.trimIndent())
            val p = GLES30.glCreateProgram()
            GLES30.glAttachShader(p, vs)
            GLES30.glAttachShader(p, fs)
            GLES30.glLinkProgram(p)
            val ok = IntArray(1)
            GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
            check(ok[0] != 0) { GLES30.glGetProgramInfoLog(p) }
            return p
        }

        private fun compile(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src)
            GLES30.glCompileShader(s)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { GLES30.glGetShaderInfoLog(s) }
            return s
        }
    }
}
