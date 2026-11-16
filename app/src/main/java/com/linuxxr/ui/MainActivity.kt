package com.linuxxr.ui

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.linuxxr.core.Guest
import com.linuxxr.core.SessionService
import com.linuxxr.core.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Control panel: install/start/stop the Arch guest, open desktop panels, agent bridge info. */
class MainActivity : Activity() {

    private var scope: CoroutineScope? = null
    private var displays = 1

    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var startStop: Button
    private lateinit var displayCount: TextView
    private lateinit var desktops: LinearLayout
    private lateinit var agentInfo: TextView
    private lateinit var log: TextView
    private lateinit var storageButton: Button
    private lateinit var logScroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        displays = getPreferences(MODE_PRIVATE).getInt(PREF_DISPLAYS, 1)
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)

        val pad = 24
        fun text(size: Float, bold: Boolean = false) = TextView(this).apply {
            textSize = size
            setTextColor(Color.WHITE)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

        status = text(16f)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        startStop = Button(this).apply { setOnClickListener { toggleSession() } }
        displayCount = text(16f)
        val displayRow = LinearLayout(this).apply {
            addView(text(16f).apply { this.text = "Escritorios: " })
            addView(Button(this@MainActivity).apply { text = "−"; setOnClickListener { setDisplays(displays - 1) } })
            addView(displayCount)
            addView(Button(this@MainActivity).apply { text = "+"; setOnClickListener { setDisplays(displays + 1) } })
        }
        desktops = LinearLayout(this)
        agentInfo = text(14f)
        val copyToken = Button(this).apply {
            text = "Copiar token de agentes"
            setOnClickListener {
                val token = Guest(this@MainActivity).agentToken
                if (token == null) {
                    Toast.makeText(this@MainActivity, "Aún no existe: inicia Linux primero", Toast.LENGTH_SHORT).show()
                } else {
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("LinuxXR agent token", token))
                    Toast.makeText(this@MainActivity, "Token copiado", Toast.LENGTH_SHORT).show()
                }
            }
        }
        storageButton = Button(this).apply { setOnClickListener { requestSharedStorage() } }
        log = text(12f).apply {
            typeface = Typeface.MONOSPACE
            setTextColor(Color.rgb(170, 220, 170))
            setTextIsSelectable(true)
        }
        logScroll = ScrollView(this).apply { addView(log) }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.rgb(18, 18, 24))
            addView(text(24f, bold = true).apply { this.text = "LinuxXR" })
            addView(text(14f).apply { this.text = "Arch Linux ARM con GPU para agentes de IA · Meta Quest 3" })
            addView(status)
            addView(progress, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(displayRow)
            addView(startStop)
            addView(desktops)
            addView(agentInfo)
            addView(copyToken)
            addView(storageButton)
            addView(logScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
        setDisplays(displays)
    }

    override fun onStart() {
        super.onStart()
        val s = MainScope()
        scope = s
        s.launch { SessionService.state.collect(::render) }
        s.launch {
            SessionService.log.collect { lines ->
                log.text = lines.joinToString("\n")
                logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        storageButton.text = if (Guest(this).hasSharedStorage) "Carpeta compartida: ~/Quest ✓ (reinicia Linux si acabas de activarla)"
        else "Activar carpeta compartida con el Quest (~/Quest)"
    }

    private fun requestSharedStorage() {
        if (Guest(this).hasSharedStorage) return
        startActivity(Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            android.net.Uri.parse("package:$packageName")))
    }

    override fun onStop() {
        scope?.cancel()
        scope = null
        super.onStop()
    }

    private fun setDisplays(n: Int) {
        displays = n.coerceIn(1, MAX_DISPLAYS)
        displayCount.text = " $displays "
        getPreferences(MODE_PRIVATE).edit().putInt(PREF_DISPLAYS, displays).apply()
    }

    private fun toggleSession() {
        when (SessionService.state.value) {
            is SessionState.Running, is SessionState.Working -> SessionService.stop(this)
            else -> SessionService.start(this, displays, "1920x1080")
        }
    }

    private fun render(state: SessionState) {
        progress.visibility = if (state is SessionState.Working) android.view.View.VISIBLE else android.view.View.GONE
        desktops.removeAllViews()
        when (state) {
            SessionState.Stopped -> {
                status.text = if (Guest(this).isInstalled) "Detenido" else "Arch Linux ARM no instalado (se descargarán ~164 MB)"
                startStop.text = "Iniciar Linux"
            }
            is SessionState.Working -> {
                status.text = state.message
                progress.isIndeterminate = state.fraction == null
                state.fraction?.let { progress.progress = (it * 1000).toInt() }
                startStop.text = "Cancelar"
            }
            is SessionState.Running -> {
                status.text = "En marcha"
                startStop.text = "Detener Linux"
                desktops.addView(Button(this).apply {
                    text = "Modo VR inmersivo (3D)"
                    setOnClickListener { startActivity(Intent(this@MainActivity, ImmersiveActivity::class.java)) }
                })
                for (n in 1..state.displays) {
                    desktops.addView(Button(this).apply {
                        text = "Abrir escritorio :$n"
                        setOnClickListener { openDesktop(n) }
                    })
                }
            }
            is SessionState.Failed -> {
                status.text = "Error: ${state.message}"
                startStop.text = "Reintentar"
            }
        }
        agentInfo.text = "Puente de agentes (MCP): http://127.0.0.1:7777/mcp · cabecera Authorization: Bearer <token>\n" +
            "Desde la nube: dentro de Linux ejecuta `linuxxr tunnel usuario@servidor`"
    }

    private fun openDesktop(n: Int) {
        // Each display is its own document task, i.e. its own Horizon OS panel; the per-display
        // data URI makes a second tap bring the existing panel back instead of duplicating it.
        startActivity(Intent(this, DesktopActivity::class.java)
            .setData(android.net.Uri.parse("linuxxr://display/$n"))
            .putExtra(DesktopActivity.EXTRA_DISPLAY, n)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT))
    }

    companion object {
        private const val PREF_DISPLAYS = "displays"
        private const val MAX_DISPLAYS = 4
    }
}
