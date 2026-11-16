package com.linuxxr.vnc

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.File

/**
 * Keeps an RFB connection to one LinuxXR display alive (reconnecting every 2 s) on its own thread.
 * Used by both the 2D panels and the immersive VR view.
 */
class DisplayConnection(
    private val socketPath: File,
    val display: Int,
    private val listener: Listener,
) {
    interface Listener : RfbClient.Listener {
        fun onConnected(client: RfbClient) {}
        fun onDisconnected(reason: String) {}
    }

    @Volatile var client: RfbClient? = null
        private set
    @Volatile private var alive = true
    @Volatile private var socket: LocalSocket? = null

    private val thread = Thread(::loop, "rfb-$display").apply { isDaemon = true }

    fun start() = apply { thread.start() }

    fun close() {
        alive = false
        runCatching { socket?.shutdownInput() }
        runCatching { socket?.close() }
    }

    private fun loop() {
        while (alive) {
            try {
                val s = LocalSocket()
                socket = s
                s.connect(LocalSocketAddress(socketPath.path, LocalSocketAddress.Namespace.FILESYSTEM))
                val c = RfbClient(s.inputStream, s.outputStream, listener)
                c.handshake()
                client = c
                listener.onConnected(c)
                c.run()
                if (alive) listener.onDisconnected("el servidor cerró la conexión")
            } catch (e: Exception) {
                if (alive) listener.onDisconnected(e.message ?: e.javaClass.simpleName)
            } finally {
                client = null
                runCatching { socket?.close() }
            }
            if (alive) Thread.sleep(2000)
        }
    }
}
