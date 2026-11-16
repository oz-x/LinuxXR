package com.linuxxr.vnc

import android.view.KeyEvent

/** Maps Android key events / characters to X11 keysyms. */
object KeySyms {
    const val SHIFT_L = 0xffe1
    const val CONTROL_L = 0xffe3
    const val ALT_L = 0xffe9
    const val SUPER_L = 0xffeb
    const val ESCAPE = 0xff1b
    const val TAB = 0xff09
    const val RETURN = 0xff0d
    const val BACKSPACE = 0xff08
    const val LEFT = 0xff51
    const val UP = 0xff52
    const val RIGHT = 0xff53
    const val DOWN = 0xff54

    private val special = mapOf(
        KeyEvent.KEYCODE_ENTER to RETURN,
        KeyEvent.KEYCODE_NUMPAD_ENTER to 0xff8d,
        KeyEvent.KEYCODE_DEL to BACKSPACE,
        KeyEvent.KEYCODE_FORWARD_DEL to 0xffff,
        KeyEvent.KEYCODE_TAB to TAB,
        KeyEvent.KEYCODE_ESCAPE to ESCAPE,
        KeyEvent.KEYCODE_DPAD_LEFT to LEFT,
        KeyEvent.KEYCODE_DPAD_UP to UP,
        KeyEvent.KEYCODE_DPAD_RIGHT to RIGHT,
        KeyEvent.KEYCODE_DPAD_DOWN to DOWN,
        KeyEvent.KEYCODE_MOVE_HOME to 0xff50,
        KeyEvent.KEYCODE_MOVE_END to 0xff57,
        KeyEvent.KEYCODE_PAGE_UP to 0xff55,
        KeyEvent.KEYCODE_PAGE_DOWN to 0xff56,
        KeyEvent.KEYCODE_INSERT to 0xff63,
        KeyEvent.KEYCODE_SHIFT_LEFT to SHIFT_L,
        KeyEvent.KEYCODE_SHIFT_RIGHT to 0xffe2,
        KeyEvent.KEYCODE_CTRL_LEFT to CONTROL_L,
        KeyEvent.KEYCODE_CTRL_RIGHT to 0xffe4,
        KeyEvent.KEYCODE_ALT_LEFT to ALT_L,
        KeyEvent.KEYCODE_ALT_RIGHT to 0xffea,
        KeyEvent.KEYCODE_META_LEFT to SUPER_L,
        KeyEvent.KEYCODE_META_RIGHT to 0xffec,
        KeyEvent.KEYCODE_CAPS_LOCK to 0xffe5,
        KeyEvent.KEYCODE_SPACE to 0x20,
        KeyEvent.KEYCODE_SYSRQ to 0xff61,
        KeyEvent.KEYCODE_BREAK to 0xff13,
        KeyEvent.KEYCODE_MENU to 0xff67,
    ) + (0 until 12).associate { (KeyEvent.KEYCODE_F1 + it) to (0xffbe + it) }

    /** Keysym for a non-printing key, or null if the key produces a character. */
    fun forKeyCode(keyCode: Int): Int? = special[keyCode]

    /** Keysym for a hardware/IME key event, or null if it has no X equivalent (e.g. BACK). */
    fun forEvent(event: KeyEvent): Int? {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return null
        return forKeyCode(event.keyCode)
            ?: event.getUnicodeChar(event.metaState).takeIf { it > 0 }?.let(::forCodePoint)
    }

    /** Keysym for a Unicode code point (Latin-1 maps directly, the rest via the 0x01000000 range). */
    fun forCodePoint(cp: Int): Int = when {
        cp == '\n'.code -> RETURN
        cp == '\t'.code -> TAB
        cp in 0x20..0x7e || cp in 0xa0..0xff -> cp
        else -> 0x01000000 or cp
    }
}
