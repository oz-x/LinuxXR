package com.linuxxr.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.linuxxr.core.Guest
import com.linuxxr.vnc.DisplayConnection
import com.linuxxr.vnc.KeySyms
import com.linuxxr.vnc.RfbClient

/** One Horizon OS panel showing one Linux X display. */
class DesktopActivity : Activity() {

    private lateinit var desktop: DesktopView
    private lateinit var status: TextView
    private val main = Handler(Looper.getMainLooper())
    private val modifierButtons = mutableMapOf<Int, Button>()
    private lateinit var rightClickButton: Button
    private var connection: DisplayConnection? = null
    private var display = 1

    private val resizeRemote = Runnable {
        val c = desktop.client ?: return@Runnable
        // Even sizes keep Xvnc/RandR happy; 1:1 pixels give the sharpest text in the panel.
        c.requestDesktopSize(desktop.width and 1.inv(), desktop.height and 1.inv())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        display = intent.getIntExtra(EXTRA_DISPLAY, 1)
        title = "LinuxXR :$display"

        desktop = DesktopView(this).apply { onModifiersChanged = ::refreshKeyBar }
        status = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            setPadding(16, 0, 16, 0)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
            addView(keyBar(), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(desktop, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
        desktop.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) scheduleResize()
        }
        setStatus("Conectando con :$display…")
        connection = connect()
    }

    private fun scheduleResize() {
        main.removeCallbacks(resizeRemote)
        main.postDelayed(resizeRemote, 400)
    }

    private fun connect() = DisplayConnection(Guest(this).displaySocket(display), display, object : DisplayConnection.Listener {
        override fun onConnected(client: RfbClient) {
            desktop.client = client
            setStatus("")
            main.post(::scheduleResize)
        }
        override fun onDisconnected(reason: String) {
            desktop.client = null
            setStatus("Sin conexión con :$display ($reason). Reintentando…")
        }
        override fun onResize(width: Int, height: Int) = desktop.requestRender()
        override fun onUpdate() = desktop.requestRender()
        override fun onServerCutText(text: String) {
            main.post { getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("LinuxXR", text)) }
        }
    }, transparentKey = ImmersiveActivity.ROOT_KEY).start()

    private fun setStatus(text: String) = main.post {
        status.text = text
        status.visibility = if (text.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun keyBar(): HorizontalScrollView {
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        fun button(label: String, onClick: (Button) -> Unit) = Button(this).apply {
            text = label
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            setOnClickListener { onClick(this) }
            bar.addView(this)
        }
        button("⌨") {
            desktop.requestFocus()
            getSystemService(InputMethodManager::class.java).showSoftInput(desktop, 0)
        }
        button("Esc") { desktop.sendKeysym(KeySyms.ESCAPE) }
        button("Tab") { desktop.sendKeysym(KeySyms.TAB) }
        for ((label, sym) in listOf("Ctrl" to KeySyms.CONTROL_L, "Alt" to KeySyms.ALT_L, "Super" to KeySyms.SUPER_L, "Shift" to KeySyms.SHIFT_L)) {
            modifierButtons[sym] = button(label) {
                if (!desktop.stickyModifiers.remove(sym)) desktop.stickyModifiers += sym
                refreshKeyBar()
            }
        }
        button("←") { desktop.sendKeysym(KeySyms.LEFT) }
        button("↑") { desktop.sendKeysym(KeySyms.UP) }
        button("↓") { desktop.sendKeysym(KeySyms.DOWN) }
        button("→") { desktop.sendKeysym(KeySyms.RIGHT) }
        rightClickButton = button("Clic der.") {
            desktop.rightClickNext = !desktop.rightClickNext
            refreshKeyBar()
        }
        button("Pegar") {
            val text = getSystemService(ClipboardManager::class.java).primaryClip
                ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)
            if (!text.isNullOrEmpty()) desktop.typeText(text)
        }
        bar.addView(status)
        return HorizontalScrollView(this).apply {
            setBackgroundColor(Color.argb(210, 16, 16, 20))
            addView(bar)
        }
    }

    private fun refreshKeyBar() {
        for ((sym, b) in modifierButtons) b.alpha = if (sym in desktop.stickyModifiers) 1f else 0.6f
        rightClickButton.alpha = if (desktop.rightClickNext) 1f else 0.6f
    }

    override fun onResume() {
        super.onResume()
        desktop.onResume()
        refreshKeyBar()
    }

    override fun onPause() {
        desktop.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        connection?.close()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_DISPLAY = "display"
    }
}
