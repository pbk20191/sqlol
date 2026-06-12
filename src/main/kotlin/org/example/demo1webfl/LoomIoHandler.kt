package org.example.demo1webfl

import io.netty.channel.IoHandle
import io.netty.channel.IoHandler
import io.netty.channel.IoHandlerContext
import io.netty.channel.IoHandlerFactory
import io.netty.channel.IoOps
import io.netty.channel.IoRegistration
import io.netty.channel.ManualIoEventLoop
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.util.concurrent.ThreadAwareExecutor
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

/**
 * Lightweight IoHandler that is friendly to user-driven ManualIoEventLoop execution.
 */
class LoomIoHandler(
    private val executor: ThreadAwareExecutor
) : IoHandler {

    private val executionThread = AtomicReference<Thread?>()
    private val registrations = ConcurrentHashMap.newKeySet<LoomIoRegistration>()
    private val runnableRegistrations = ConcurrentLinkedQueue<LoomIoRegistration>()
    private val sequence = AtomicLong(0)

    override fun run(context: IoHandlerContext): Int {
        executionThread.compareAndSet(null, Thread.currentThread())

        var handled = 0
        val deadlineNanos = context.deadlineNanos()

        while (true) {
            val registration = runnableRegistrations.poll() ?: break
            if (!registration.isValid()) {
                continue
            }
            handled += registration.runSubmittedOps()

            if (deadlineNanos >= 0 && System.nanoTime() >= deadlineNanos) {
                break
            }
        }

        if (handled == 0 && context.canBlock()) {
            LockSupport.parkNanos(this, context.delayNanos(System.nanoTime()))
        }

        if (context.shouldReportActiveIoTime()) {
            context.reportActiveIoTime(0)
        }
        return handled
    }

    override fun register(handle: IoHandle): IoRegistration {
        val registration = LoomIoRegistration(handle)
        if (!registrations.add(registration)) {
            throw IllegalStateException("Registration already exists")
        }
        handle.registered()
        return registration
    }

    override fun wakeup() {
        if (!executor.isExecutorThread(Thread.currentThread())) {
            executionThread.get()?.let(LockSupport::unpark)
        }
    }

    override fun isCompatible(handleType: Class<out IoHandle>): Boolean {
        return IoHandle::class.java.isAssignableFrom(handleType)
    }

    override fun prepareToDestroy() {
        registrations.forEach { it.cancel() }
        registrations.clear()
        runnableRegistrations.clear()
    }

    override fun destroy() {

        // No external resources owned by this handler.
    }

    inner class LoomIoRegistration(
        private val handle: IoHandle
    ) : IoRegistration {

        private val canceled = AtomicBoolean(false)
        private val submittedOps = ConcurrentLinkedQueue<IoOps>()

        @Suppress("UNCHECKED_CAST")
        override fun <T> attachment(): T {
            return handle as T
        }

        override fun submit(ops: IoOps): Long {
            if (!isValid()) {
                return -1
            }
            submittedOps.offer(ops)
            runnableRegistrations.offer(this)
            wakeup()
            return sequence.incrementAndGet()
        }

        override fun isValid(): Boolean {
            return !canceled.get()
        }

        override fun cancel(): Boolean {
            if (!canceled.compareAndSet(false, true)) {
                return false
            }

            val task = Runnable {
                if (registrations.remove(this)) {
                    try {
                        handle.unregistered()
                    } finally {
                        try {
                            handle.close()
                        } catch (_: Exception) {
                            // Ignore close errors while tearing down.
                        }
                    }
                }
            }

            if (executor.isExecutorThread(Thread.currentThread())) {
                task.run()
            } else {
                executor.execute(task)
            }
            wakeup()
            return true
        }

        internal fun runSubmittedOps(): Int {
            var count = 0
            while (true) {
                val ops = submittedOps.poll() ?: break
                if (!isValid()) {
                    break
                }
                handle.handle(this, LoomSubmittedIoEvent(ops))
                count++
            }
            return count
        }
    }

    companion object {

        fun newFactory(): IoHandlerFactory {
            return IoHandlerFactory { executor -> LoomIoHandler(executor) }
        }

        fun newManualEventLoop(owningThread: Thread? = null): ManualIoEventLoop {
            return ManualIoEventLoop(null, owningThread, newFactory())
        }
    }
}