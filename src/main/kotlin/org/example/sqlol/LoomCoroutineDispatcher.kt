package org.example.sqlol

import kotlinx.coroutines.*
import org.jetbrains.annotations.BlockingExecutor
import java.util.concurrent.*
import kotlin.coroutines.CoroutineContext
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlin.time.toJavaDuration

@BlockingExecutor
@OptIn(InternalCoroutinesApi::class)
class LoomCoroutineDispatcher internal constructor(
    override val executor: ExecutorService,
    private val timeSource: TimeSource.WithComparableMarks,
    private val parent: LoomScheduledExecutorService? = null
): ExecutorCoroutineDispatcher(), Delay {

    init {

        require(executor::class.java.name == "java.util.concurrent.ThreadPerTaskExecutor") {
            "executor must be an instance of java.util.concurrent.ThreadPerTaskExecutor"
        }
    }

    constructor(
        builder: Thread.Builder.OfVirtual = Thread.ofVirtual().name("virtual-dispatcher",0),
        timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic
    ): this(Executors.newThreadPerTaskExecutor(builder.factory()), timeSource)

    val scheduledExecutorService:ScheduledExecutorService by lazy(LazyThreadSafetyMode.NONE) {
        parent ?: LoomScheduledExecutorService(executor, timeSource, this)
    }

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        try {

            executor.execute(block)
        } catch (e: RejectedExecutionException) {
//            println("Task rejected on dispatch $context $block")
            context.cancel(CancellationException("The task was rejected", e))
            Thread.startVirtualThread(block)
        }
    }

    override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
        val timeMark = timeSource.markNow() + timeMillis.milliseconds
        val delayTask = FutureTask(Sleeping(timeMark), Unit)

        @OptIn(ExperimentalCoroutinesApi::class)
        val wrappedTask = FutureTask {
            delayTask.run()
            with(continuation) {
                resumeUndispatched(Unit)
            }


        }
        try {
            executor.execute(wrappedTask)
            continuation.invokeOnCancellation {
                delayTask.cancel(true)
            }
        } catch (e:RejectedExecutionException) {
//            println("Task rejected on scheduleResumeAfterDelay $continuation")
            continuation.cancel(e)
        }
    }

    override fun invokeOnTimeout(timeMillis: Long, block: Runnable, context: CoroutineContext): DisposableHandle {
        val delay = timeMillis.milliseconds
        val expectedMark = timeSource.markNow() + delay
        val task = DelayedTask(block, expectedMark)
        try {
            executor.execute(task)
        } catch (e: RejectedExecutionException) {
//            println("Task rejected on invokeOnTimeout $context $block")
            context.cancel(CancellationException("The task was rejected", e))
            task.block.sleep.cancel(false)
            Thread.startVirtualThread(block)
        }
        return task
    }

    override fun close() = scheduledExecutorService.close()

    private data class Sleeping(
        private val expectedMark: ComparableTimeMark
    ): Runnable {
        override fun run() {
            try {
                val remaining = expectedMark.elapsedNow().unaryMinus().coerceAtLeast(Duration.ZERO)
                Thread.sleep(remaining.toJavaDuration())
            } catch (e: InterruptedException) {
                throw e
            }
        }

    }

    private class WrappedRunnable(
        val block: Runnable,
        expectedMark: ComparableTimeMark
    ): Runnable {

        val sleep = FutureTask(Sleeping(expectedMark), Unit)

        override fun run() {
            sleep.run()
            block.run()
        }

        override fun toString(): String {
            return sleep.toString() + block.toString()
        }

    }

    private class DelayedTask private constructor(
        val block: WrappedRunnable,
    ): FutureTask<Unit>(block, Unit), DisposableHandle {

        constructor(block: Runnable, expectedMark: ComparableTimeMark):this(WrappedRunnable(block, expectedMark))

        override fun dispose() {
            block.sleep.cancel(true)
            cancel(false)
        }

    }

}