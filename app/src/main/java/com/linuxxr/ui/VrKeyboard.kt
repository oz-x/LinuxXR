package com.linuxxr.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.linuxxr.vnc.KeySyms
import com.linuxxr.vnc.RfbClient

/**
 * Virtual keyboard for the immersive mode. Drawn with Android Canvas into a bitmap that the
 * native side shows as an OpenXR quad layer; the controller ray reports (u, v) hits here.
 * Keys send X keysyms, so the result does not depend on the guest keyboard layout.
 */
class VrKeyboard {

    private class Key(val label: String, val sym: Int, val width: Float = 1f, val shifted: String? = null) {
        val rect = RectF()
    }

    private val rows: List<List<Key>> = listOf(
        listOf(Key("Esc", KeySyms.ESCAPE)) + "1234567890-=".map { charKey(it) } + Key("⌫", KeySyms.BACKSPACE, 1.6f),
        listOf(Key("Tab", KeySyms.TAB, 1.4f)) + "qwertyuiop[]\\".map { charKey(it) },
        listOf(Key("Bloq", CAPS, 1.7f)) + "asdfghjklñ;'".map { charKey(it) } + Key("Enter", KeySyms.RETURN, 1.9f),
        listOf(Key("Shift", KeySyms.SHIFT_L, 2.2f)) + "zxcvbnm,./".map { charKey(it) } +
            Key("↑", KeySyms.UP) + Key("Shift", KeySyms.SHIFT_L, 1.4f),
        listOf(
            Key("Ctrl", KeySyms.CONTROL_L, 1.4f), Key("Super", KeySyms.SUPER_L, 1.4f), Key("Alt", KeySyms.ALT_L, 1.4f),
            Key("🎤 Dictar", DICTATE, 2.2f),
            Key("", 0x20, 5.0f), Key("←", KeySyms.LEFT), Key("↓", KeySyms.DOWN), Key("→", KeySyms.RIGHT),
            Key("✕", CLOSE, 1.6f),
        ),
    )

    val bitmap: Bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
    @Volatile var dirty = true
        private set

    /** Dictation state shown in the top bar (set from the UI thread). */
    @Volatile var dictating = false
        set(value) { field = value; dirty = true }
    @Volatile var status = "Teclado LinuxXR · 🎤 Dictar para escribir con la voz"
        set(value) { field = value; dirty = true }

    private var hovered: Key? = null
    private var pressed: Key? = null
    private var shift = false
    private var caps = false
    private val sticky = linkedSetOf<Int>()

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 22, 24, 32) }
    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    init {
        layout()
    }

    private fun charKey(c: Char): Key {
        val shifted = SHIFTED[c] ?: if (c.isLetter()) c.uppercase() else null
        return Key(c.toString(), KeySyms.forCodePoint(c.code), shifted = shifted)
    }

    private fun layout() {
        val pad = 14f
        val gap = 10f
        val rowH = (HEIGHT - STATUS_H - 2 * pad - gap * (rows.size - 1)) / rows.size
        rows.forEachIndexed { r, row ->
            val units = row.sumOf { it.width.toDouble() }.toFloat()
            val unitW = (WIDTH - 2 * pad - gap * (row.size - 1)) / units
            var x = pad
            val y = STATUS_H + pad + r * (rowH + gap)
            row.forEach { k ->
                val w = unitW * k.width
                k.rect.set(x, y, x + w, y + rowH)
                x += w + gap
            }
        }
    }

    /** Redraws if needed; returns true when the bitmap changed since the last call. */
    fun render(): Boolean {
        if (!dirty) return false
        dirty = false
        val c = Canvas(bitmap)
        c.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
        c.drawRoundRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), 28f, 28f, bg)
        // Status bar: live dictation transcript (partial results) or a hint.
        text.textSize = 34f
        text.color = if (dictating) Color.rgb(255, 120, 120) else Color.rgb(170, 176, 196)
        val line = (if (dictating) "● " else "") + status
        val shown = if (text.measureText(line) > WIDTH - 60) "…" + line.takeLast(80) else line
        c.drawText(shown, WIDTH / 2f, STATUS_H / 2f + 20f, text)
        text.color = Color.WHITE
        rows.flatten().forEach { k ->
            val active = k == pressed || k.sym in sticky || (k.sym == KeySyms.SHIFT_L && shift) || (k.sym == CAPS && caps)
            keyPaint.color = when {
                k.sym == DICTATE && dictating -> Color.rgb(210, 50, 60)
                active -> Color.rgb(64, 132, 255)
                k == hovered -> Color.rgb(78, 84, 104)
                else -> Color.rgb(46, 50, 64)
            }
            c.drawRoundRect(k.rect, 12f, 12f, keyPaint)
            val upper = shift xor caps
            val label = if (upper && k.shifted != null) k.shifted else k.label
            text.textSize = if (label.length > 2) 30f else 40f
            c.drawText(label, k.rect.centerX(), k.rect.centerY() - (text.descent() + text.ascent()) / 2, text)
        }
        return true
    }

    private fun keyAt(u: Float, v: Float): Key? {
        val x = u * WIDTH
        val y = v * HEIGHT
        return rows.flatten().firstOrNull { it.rect.contains(x, y) }
    }

    /**
     * Ray hit at (u, v) with the trigger state. Returns bit 1 when the hovered key changed (haptic
     * tick), bit 2 when the keyboard should close and bit 4 to toggle dictation.
     */
    fun pointer(u: Float, v: Float, down: Boolean, client: RfbClient?): Int {
        var result = 0
        val k = keyAt(u, v)
        if (k != hovered) {
            hovered = k
            dirty = true
            if (k != null) result = result or 1
        }
        if (down && pressed == null && k != null) {
            pressed = k
            dirty = true
            when (k.sym) {
                CLOSE -> result = result or 2
                DICTATE -> result = result or 4
                else -> press(k, client)
            }
        } else if (!down && pressed != null) {
            pressed = null
            dirty = true
        }
        return result
    }

    fun leave() {
        if (hovered != null || pressed != null) dirty = true
        hovered = null
        pressed = null
    }

    private fun press(k: Key, client: RfbClient?) {
        when (k.sym) {
            CAPS -> caps = !caps
            KeySyms.SHIFT_L -> shift = !shift
            KeySyms.CONTROL_L, KeySyms.ALT_L, KeySyms.SUPER_L -> if (!sticky.remove(k.sym)) sticky += k.sym
            else -> {
                val c = client ?: return
                val upper = shift xor caps
                val sym = if (upper && k.shifted != null) KeySyms.forCodePoint(k.shifted.codePointAt(0)) else k.sym
                // With Ctrl/Alt/Super held, send Shift explicitly so combos like Ctrl+Shift+T work.
                val mods = sticky.toList() + if (shift && sticky.isNotEmpty()) listOf(KeySyms.SHIFT_L) else emptyList()
                mods.forEach { c.sendKey(it, true) }
                c.sendKey(sym, true)
                c.sendKey(sym, false)
                mods.asReversed().forEach { c.sendKey(it, false) }
                sticky.clear()
                shift = false
            }
        }
    }

    companion object {
        const val WIDTH = 1600
        const val HEIGHT = 650
        private const val STATUS_H = 90f
        private const val CAPS = -1
        private const val CLOSE = -2
        private const val DICTATE = -3
        private val SHIFTED = mapOf(
            '1' to "!", '2' to "@", '3' to "#", '4' to "$", '5' to "%", '6' to "^", '7' to "&", '8' to "*",
            '9' to "(", '0' to ")", '-' to "_", '=' to "+", '[' to "{", ']' to "}", '\\' to "|", ';' to ":",
            '\'' to "\"", ',' to "<", '.' to ">", '/' to "?", 'ñ' to "Ñ",
        )
    }
}
