package org.example.sqlol

import io.netty.channel.IoRegistration
import io.netty.channel.ManualIoEventLoop
import org.junit.jupiter.api.Test
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class LoomSocketIoHandleTests {

    @Test
    fun serverClientHandleCanAcceptReadAndWrite() {
        val loop = LoomIoHandler.newManualEventLoop()
        LoopDriver(loop).use {
            ServerSocket(0).use { server ->
                val acceptedRegRef = AtomicReference<IoRegistration?>()
                val receivedFromClient = LinkedBlockingQueue<String>()

                val serverHandle = ServerSocketIoHandle(server) { acceptedSocket ->
                    val acceptedHandle = ClientSocketIoHandle(
                        socket = acceptedSocket,
                        onRead = { bytes ->
                            receivedFromClient.offer(String(bytes, StandardCharsets.UTF_8))
                        }
                    )
                    val acceptedReg = loop.register(acceptedHandle).get(1, TimeUnit.SECONDS)
                    acceptedRegRef.set(acceptedReg)
                }

                val serverRegistration = loop.register(serverHandle).get(1, TimeUnit.SECONDS)
                serverRegistration.submit(LoomSocketOps.Accept)

                Socket("127.0.0.1", server.localPort).use { client ->
                    client.soTimeout = 1_000
                    client.getOutputStream().write("hello-from-client".toByteArray(StandardCharsets.UTF_8))
                    client.getOutputStream().flush()

                    val acceptedReg = waitForRegistration(acceptedRegRef)
                    acceptedReg.submit(LoomSocketOps.Read)

                    val serverRead = receivedFromClient.poll(2, TimeUnit.SECONDS)
                    assertEquals("hello-from-client", serverRead)

                    acceptedReg.submit(LoomSocketOps.Write("hello-from-server".toByteArray(StandardCharsets.UTF_8)))
                    val buffer = ByteArray(64)
                    val read = client.getInputStream().read(buffer)
                    assertEquals("hello-from-server", String(buffer, 0, read, StandardCharsets.UTF_8))
                }
            }
        }
    }

    @Test
    fun datagramHandleCanSendAndReceive() {
        val loop = LoomIoHandler.newManualEventLoop()
        LoopDriver(loop).use {
            DatagramSocket(0).use { receiver ->
                DatagramSocket(0).use { sender ->
                    val queue = LinkedBlockingQueue<String>()

                    val receiverReg = loop.register(
                        DatagramSocketIoHandle(receiver) { packet ->
                            queue.offer(String(packet.data, packet.offset, packet.length, StandardCharsets.UTF_8))
                        }
                    ).get(1, TimeUnit.SECONDS)

                    val senderReg = loop.register(DatagramSocketIoHandle(sender)).get(1, TimeUnit.SECONDS)

                    senderReg.submit(
                        LoomSocketOps.SendTo(
                            InetSocketAddress("127.0.0.1", receiver.localPort),
                            "ping-datagram".toByteArray(StandardCharsets.UTF_8)
                        )
                    )
                    receiverReg.submit(LoomSocketOps.Receive)

                    assertEquals("ping-datagram", queue.poll(2, TimeUnit.SECONDS))
                }
            }
        }
    }

    private fun waitForRegistration(ref: AtomicReference<IoRegistration?>): IoRegistration {
        repeat(200) {
            val registration = ref.get()
            if (registration != null) {
                return registration
            }
            Thread.sleep(10)
        }
        return assertNotNull(ref.get(), "accepted registration was not created in time")
    }

    private class LoopDriver(
        private val loop: ManualIoEventLoop
    ) : AutoCloseable {

        private val running = AtomicBoolean(true)
        private val ownerThread = Thread.ofVirtual().name("manual-io-loop-test-", 0).start {
            loop.setOwningThread(Thread.currentThread())
            while (running.get()) {
                loop.run(5_000_000L, 0L)
            }
            loop.shutdownGracefully(0L, 0L, TimeUnit.MILLISECONDS)
            while (!loop.isTerminated) {
                loop.runNow()
            }
        }

        override fun close() {
            running.set(false)
            loop.wakeup()
            ownerThread.join(2_000)
        }
    }
}

