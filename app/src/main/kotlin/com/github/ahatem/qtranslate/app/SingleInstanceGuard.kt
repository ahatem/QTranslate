package com.github.ahatem.qtranslate.app

import java.io.PrintWriter
import java.net.BindException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

object SingleInstanceGuard {

    private const val PORT = 49185
    private const val FOCUS_SIGNAL = "FOCUS"
    private var serverSocket: ServerSocket? = null


    fun tryLock(onFocusRequested: () -> Unit): Boolean {
        return try {
            serverSocket = ServerSocket(PORT, 1, InetAddress.getByName("localhost"))
            Thread {
                while (true) {
                    runCatching {
                        serverSocket?.accept()?.use { client ->
                            val signal = client.getInputStream().bufferedReader().readLine()
                            if (signal == FOCUS_SIGNAL) onFocusRequested()
                        }
                    }
                }
            }.apply { isDaemon = true }.start()
            true
        } catch (_: BindException) {
            runCatching {
                Socket(InetAddress.getByName("localhost"), PORT).use { socket ->
                    PrintWriter(socket.getOutputStream(), true).println(FOCUS_SIGNAL)
                }
            }
            false
        }
    }

    fun release() {
        serverSocket?.close()
    }
}