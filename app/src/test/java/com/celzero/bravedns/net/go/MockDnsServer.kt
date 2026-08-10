package com.celzero.bravedns.net.go

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * A simple Mock DNS Server for testing purposes.
 * It listens on a random port and can be configured to return specific responses.
 */
class MockDnsServer {
    private var socket: DatagramSocket? = null
    private val running = AtomicBoolean(false)
    var port: Int = 0
        private set

    fun start() {
        socket = DatagramSocket(0) // Random port
        port = socket!!.localPort
        running.set(true)

        thread(name = "MockDnsServer") {
            val buffer = ByteArray(512)
            while (running.get()) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket?.receive(packet)
                    
                    // Basic DNS response logic can be added here if needed
                    // For now, just a dummy echo or empty response
                    val response = packet.data.copyOf(packet.length)
                    val responsePacket = DatagramPacket(response, response.size, packet.address, packet.port)
                    socket?.send(responsePacket)
                } catch (e: Exception) {
                    if (running.get()) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    fun stop() {
        running.set(false)
        socket?.close()
        socket = null
    }
}
