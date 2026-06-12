package org.example.demo1webfl

import io.netty.channel.IoEvent
import io.netty.channel.IoHandle
import io.netty.channel.IoOps
import io.netty.channel.IoRegistration
import io.netty.channel.nio.NioIoHandle
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Event wrapper emitted by LoomIoHandler when a registration submits IoOps.
 */
class LoomSubmittedIoEvent(val ops: IoOps) : IoEvent

/**
 * Small operation set used by the custom java.net based IoHandle implementations.
 */
sealed interface LoomSocketOps : IoOps {
    data object Accept : LoomSocketOps
    data object Read : LoomSocketOps
    class Write(val payload: ByteArray) : LoomSocketOps
    data object Receive : LoomSocketOps
    class SendTo(val target: SocketAddress, val payload: ByteArray) : LoomSocketOps
}

class ServerSocketIoHandle(
    private val serverSocket: ServerSocket,
    private val acceptTimeoutMillis: Int = 50,
    private val onAccept: (Socket) -> Unit = {}
) : IoHandle {

    private val closed = AtomicBoolean(false)

    override fun handle(registration: IoRegistration, event: IoEvent) {
        if (closed.get()) {
            return
        }
        NioIoHandle::handle
        val ops = (event as? LoomSubmittedIoEvent)?.ops as? LoomSocketOps ?: return
        if (ops != LoomSocketOps.Accept) {
            return
        }

        try {
            val accepted = serverSocket.accept()
            accepted.soTimeout = acceptTimeoutMillis
            onAccept(accepted)
        } catch (_: SocketTimeoutException) {
            // No connection ready in this cycle.
        }
    }

    override fun registered() {
        serverSocket.soTimeout = acceptTimeoutMillis
    }

    override fun unregistered() {
        closeQuietly()
    }

    override fun close() {
        closeQuietly()
    }

    private fun closeQuietly() {
        if (closed.compareAndSet(false, true)) {
            runCatching { serverSocket.close() }
        }
    }
}

class ClientSocketIoHandle(
    private val socket: Socket,
    private val ioTimeoutMillis: Int = 50,
    private val readBufferSize: Int = 4096,
    private val onRead: (ByteArray) -> Unit = {}
) : IoHandle {

    private val closed = AtomicBoolean(false)

    override fun handle(registration: IoRegistration, event: IoEvent) {
        if (closed.get()) {
            return
        }
        val ops = (event as? LoomSubmittedIoEvent)?.ops as? LoomSocketOps ?: return

        when (ops) {
            LoomSocketOps.Read -> readOnce()
            is LoomSocketOps.Write -> writeOnce(ops.payload)
            else -> Unit
        }
    }

    override fun registered() {
        socket.soTimeout = ioTimeoutMillis
    }

    override fun unregistered() {
        closeQuietly()
    }

    override fun close() {
        closeQuietly()
    }

    private fun readOnce() {
        val input = socket.getInputStream()
        val size = input.available().coerceAtLeast(1).coerceAtMost(readBufferSize)
        val buffer = ByteArray(size)

        try {
            val read = input.read(buffer)
            when {
                read > 0 -> onRead(buffer.copyOf(read))
                read < 0 -> closeQuietly()
            }
        } catch (_: SocketTimeoutException) {
            // No bytes available in this cycle.
        }
    }

    private fun writeOnce(payload: ByteArray) {
        val output = socket.getOutputStream()
        output.write(payload)
        output.flush()
    }

    private fun closeQuietly() {
        if (closed.compareAndSet(false, true)) {
            runCatching { socket.close() }
        }
    }
}

class DatagramSocketIoHandle(
    private val socket: DatagramSocket,
    private val ioTimeoutMillis: Int = 50,
    private val receiveBufferSize: Int = 2048,
    private val onReceive: (DatagramPacket) -> Unit = {}
) : IoHandle {

    private val closed = AtomicBoolean(false)

    override fun handle(registration: IoRegistration, event: IoEvent) {
        if (closed.get()) {
            return
        }
        val ops = (event as? LoomSubmittedIoEvent)?.ops as? LoomSocketOps ?: return

        when (ops) {
            LoomSocketOps.Receive -> receiveOnce()
            is LoomSocketOps.SendTo -> sendOnce(ops)
            else -> Unit
        }
    }

    override fun registered() {
        socket.soTimeout = ioTimeoutMillis
    }

    override fun unregistered() {
        closeQuietly()
    }

    override fun close() {
        closeQuietly()
    }

    private fun receiveOnce() {
        val packet = DatagramPacket(ByteArray(receiveBufferSize), receiveBufferSize)
        try {
            socket.receive(packet)
            onReceive(packet)
        } catch (_: SocketTimeoutException) {
            // No datagram available in this cycle.
        }
    }

    private fun sendOnce(ops: LoomSocketOps.SendTo) {
        val packet = DatagramPacket(ops.payload, ops.payload.size, ops.target)
        socket.send(packet)
    }

    private fun closeQuietly() {
        if (closed.compareAndSet(false, true)) {
            runCatching { socket.close() }
        }
    }
}

