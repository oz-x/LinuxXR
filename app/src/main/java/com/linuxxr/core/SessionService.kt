package com.linuxxr.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.linuxxr.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

sealed interface SessionState {
    data object Stopped : SessionState
    data class Working(val message: String, val fraction: Float? = null) : SessionState
    data class Running(val displays: Int) : SessionState
    data class Failed(val message: String) : SessionState
}

/**
 * Owns the Linux guest: installs the rootfs, provisions it on first boot, then keeps
 * `session.sh` (Xvnc displays + agentd) alive as a foreground service.
 */
class SessionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var process: Process? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSession()
                return START_NOT_STICKY
            }
            else -> {
                val displays = intent?.getIntExtra(EXTRA_DISPLAYS, 1) ?: 1
                val geometry = intent?.getStringExtra(EXTRA_GEOMETRY) ?: "1920x1080"
                startForeground(NOTIFICATION_ID, notification("Iniciando Linux…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                if (job?.isActive != true) job = scope.launch { runSession(displays, geometry) }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun runSession(displays: Int, geometry: String) {
        val guest = Guest(this)
        try {
            if (!guest.isInstalled) {
                RootfsInstaller(guest, cacheDir).install { msg, fraction -> _state.value = SessionState.Working(msg, fraction) }
            }
            guest.prepare()
            _state.value = SessionState.Working("Aprovisionando Debian + i3 (primera vez: varios minutos)…")
            val provisionCode = runGuest(guest, "/bin/bash", "/usr/local/lib/linuxxr/provision.sh")
            if (provisionCode != 0) throw IllegalStateException("El aprovisionamiento falló (código $provisionCode). Revisa el log.")

            _state.value = SessionState.Working("Arrancando escritorios…")
            val code = runGuest(guest, "/bin/bash", "/usr/local/lib/linuxxr/session.sh", displays.toString(), geometry) { line ->
                if (line.contains("SESSION_READY")) {
                    _state.value = SessionState.Running(displays)
                    updateNotification("Linux en marcha · $displays escritorio(s)")
                }
            }
            _state.value = if (code == 0 || code == 143) SessionState.Stopped
            else SessionState.Failed("La sesión terminó (código $code)")
        } catch (e: kotlinx.coroutines.CancellationException) {
            _state.value = SessionState.Stopped
            throw e
        } catch (e: Exception) {
            log("ERROR: ${e.message}")
            _state.value = SessionState.Failed(e.message ?: e.toString())
        } finally {
            process?.destroy()
            process = null
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    private suspend fun runGuest(guest: Guest, vararg cmd: String, onLine: (String) -> Unit = {}): Int {
        val p = guest.process(*cmd).start()
        process = p
        return runInterruptible {
            try {
                p.inputStream.bufferedReader().forEachLine { line ->
                    log(line)
                    onLine(line)
                }
                p.waitFor()
            } catch (e: InterruptedException) {
                p.destroy()
                throw e
            }
        }
    }

    private fun stopSession() {
        process?.destroy()
        job?.cancel()
        job = null
        _state.value = SessionState.Stopped
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        process?.destroy()
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Sesión Linux", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("LinuxXR")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    companion object {
        private const val CHANNEL = "session"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.linuxxr.STOP"
        private const val EXTRA_DISPLAYS = "displays"
        private const val EXTRA_GEOMETRY = "geometry"
        private const val MAX_LOG_LINES = 400

        private val _state = MutableStateFlow<SessionState>(SessionState.Stopped)
        val state: StateFlow<SessionState> = _state.asStateFlow()

        private val _log = MutableStateFlow<List<String>>(emptyList())
        val log: StateFlow<List<String>> = _log.asStateFlow()

        private fun log(line: String) = _log.update { (it + line).takeLast(MAX_LOG_LINES) }

        fun start(context: Context, displays: Int, geometry: String) {
            context.startForegroundService(Intent(context, SessionService::class.java)
                .putExtra(EXTRA_DISPLAYS, displays)
                .putExtra(EXTRA_GEOMETRY, geometry))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, SessionService::class.java).setAction(ACTION_STOP))
        }
    }
}
