package org.example.demo1webfl

import org.jetbrains.annotations.BlockingExecutor
import java.io.Closeable
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.*

/**
 *  Scheduler which uses JVM Scheduler to schedule jobs
 *
 * */
@BlockingExecutor
class LoomScheduledExecutorService internal constructor(
    private val actualImp: ExecutorService,
    private val timeSource: TimeSource.WithComparableMarks,
    private val parent:LoomCoroutineDispatcher? = null
): AbstractExecutorService(), ScheduledExecutorService, Closeable {
//    val source: InstantSource = Clock.systemUTC()


    constructor(
        builder: Thread.Builder.OfVirtual = Thread.ofVirtual().name("virtual-dispatcher",0),
        timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic
    ):this(Executors.newThreadPerTaskExecutor(builder.factory()), timeSource)

    private val sequencer = AtomicLong()

    val dispatcher: LoomCoroutineDispatcher by lazy(LazyThreadSafetyMode.NONE) {
        parent ?: LoomCoroutineDispatcher(actualImp, timeSource, this)
    }

    override fun execute(command: Runnable) =
        actualImp.execute(command)

    override fun shutdown() =
        actualImp.shutdown()

    override fun awaitTermination(timeout: Long, unit: TimeUnit) =
        actualImp.awaitTermination(timeout, unit)

    override fun isShutdown() = actualImp.isShutdown

    override fun isTerminated() = actualImp.isTerminated

    override fun shutdownNow() = actualImp.shutdownNow()


    override fun schedule(
        command: Runnable,
        delay: Long,
        unit: TimeUnit
    ): ScheduledFuture<*> {
//        val ins = source.instant()
        val duration = delay.coerceAtLeast(0L).toDuration(unit.toDurationUnit())
        val mark = timeSource.markNow() + duration
        val task:RunnableScheduledFuture<Unit> = DelayedTask(
            command,
            Unit,
            mark,
            sequencer.getAndIncrement()
        )
        execute(task)

        return task
    }

    override fun <V> schedule(callable: Callable<V>, delay: Long, unit: TimeUnit): ScheduledFuture<V> {
        val duration = delay.coerceAtLeast(0L).toDuration(unit.toDurationUnit())

        val task: RunnableScheduledFuture<V> = DelayedTask(
            callable,
            timeSource.markNow() + duration,
            sequencer.getAndIncrement()
        )
        execute(task)
        return task
    }

    override fun scheduleAtFixedRate(
        command: Runnable,
        initialDelay: Long,
        period: Long,
        unit: TimeUnit
    ): ScheduledFuture<*> {
        /**
         *
         * initialDelay,
         * then initialDelay + period,
         * then initialDelay + 2 * period
         * */
        val taskPeriod = period.toDuration(unit.toDurationUnit())
        if (taskPeriod <= Duration.ZERO) {
            throw IllegalArgumentException("period <= 0")
        }

        val mark = timeSource.markNow() +
                initialDelay
                    .toDuration(unit.toDurationUnit())
                    .coerceAtLeast(Duration.ZERO)
        val task: RunnableScheduledFuture<Unit> = FixedRateTask(
            command,
            Unit,
            mark,
            taskPeriod,
            sequencer.getAndIncrement(),
            actualImp
        )
        execute(task)
        return task
    }

    override fun scheduleWithFixedDelay(
        command: Runnable,
        initialDelay: Long,
        delay: Long,
        unit: TimeUnit
    ): ScheduledFuture<*> {
        /**
         *  initial delay,
         *  and subsequently
         *  with the given delay between
         *  the termination of one execution
         *  and the commencement of the next.
         * */

        val taskPeriod = delay.toDuration(unit.toDurationUnit())
        if (taskPeriod <= Duration.ZERO) {
            throw IllegalArgumentException("period <= 0")
        }
        val mark = timeSource.markNow() +
                initialDelay
                    .toDuration(unit.toDurationUnit())
                    .coerceAtLeast(Duration.ZERO)
        val task: RunnableScheduledFuture<Unit> = PeriodicTask(
            command,
            Unit,
            mark,
            taskPeriod,
            sequencer.getAndIncrement(),
            actualImp
        )
        execute(task)
        return task
    }

    override fun close() = super<AbstractExecutorService>.close()
//
//    override fun equals(other: Any?): Boolean {
//        return if (other is LoomSchedulerExecutorService){
//            other.actualImp == actualImp
//        } else {
//            false
//        }
//    }

    private data class Sleeping(
        private val expectedMark: ComparableTimeMark
    ): Runnable {
        override fun run() {
            val remaining = expectedMark.elapsedNow().unaryMinus().coerceAtLeast(Duration.ZERO)
            Thread.sleep(remaining.toJavaDuration())
        }
    }

    sealed class BaseTask<V>: FutureTask<V>, RunnableScheduledFuture<V> {
        val mark: ComparableTimeMark
        val sequenceNumber:Long
        constructor(
            r:Runnable,
            result: V,
            sequenceNumber:Long,
            mark: ComparableTimeMark
        ): super(r, result) {
            this.sequenceNumber = sequenceNumber
            this.mark = mark

        }

        constructor(
            c: Callable<V>,
            sequenceNumber:Long,
            mark: ComparableTimeMark
        ): super(c) {
            this.sequenceNumber = sequenceNumber
            this.mark = mark
        }

        override fun isPeriodic() = false

        override fun compareTo(other: Delayed): Int {
            if (other == this) {
                return 0
            }
            return if (other is BaseTask<*>) {
                if (other.expectedMark > expectedMark || other.expectedMark < expectedMark) {
                    expectedMark.compareTo(other.expectedMark)
                } else {
                    sequenceNumber.compareTo(other.sequenceNumber)
                }
            } else {
                val diff = getDelay(TimeUnit.NANOSECONDS) - other.getDelay(TimeUnit.NANOSECONDS)
                if (diff < 0) -1 else if (diff > 0) 1 else 0
            }
        }

        override fun getDelay(unit: TimeUnit): Long {
            return expectedMark.elapsedNow().unaryMinus()
                .toLong(unit.toDurationUnit())
        }

        open val expectedMark:ComparableTimeMark
            get() = mark
    }

    private class DelayedTask<V> : BaseTask<V> {

        constructor(
            r:Runnable,
            result: V,
            triggerTime: ComparableTimeMark,
            sequenceNumber:Long
        ): super(r, result, sequenceNumber, triggerTime)

        constructor(
            c: Callable<V>,
            triggerTime: ComparableTimeMark,
            sequenceNumber:Long
        ): super(c, sequenceNumber, triggerTime)


        override fun isPeriodic() = false

        private val childTask = FutureTask(Sleeping(mark), Unit)

        override fun run() {
            childTask.run()
            if (childTask.isCancelled) {
                super.cancel(false)
            }
            super.run()
        }

        override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
            childTask.cancel(true)
            return super.cancel(mayInterruptIfRunning)
        }

    }

    private class FixedRateTask<V>: BaseTask<V> {
        private val period: Duration
        private val rescheduler: ExecutorService

        @Volatile
        private var nextMark: ComparableTimeMark

        @Volatile
        private var sleepTask: FutureTask<Unit>? = null

        constructor(
            r: Runnable,
            result: V,
            triggerTime: ComparableTimeMark,
            period: Duration,
            sequenceNumber: Long,
            rescheduler: ExecutorService,
        ) : super(r, result, sequenceNumber, triggerTime) {
            this.period = period
            this.nextMark = mark
            this.rescheduler = rescheduler
        }

        override fun run() {
            if (isCancelled) return

            val sleeper = FutureTask(Sleeping(nextMark), Unit)
            sleepTask = sleeper
            sleeper.run()
            sleepTask = null

            if (isCancelled || sleeper.isCancelled) return

            val executed = runAndReset()
            if (!executed || isCancelled || rescheduler.isShutdown) return

            nextMark += period
            try {
                rescheduler.execute(this)
            } catch (_: RejectedExecutionException) {
                cancel(false)
            }
        }

        override fun isPeriodic() = true

        override val expectedMark get() = nextMark

        override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
            sleepTask?.cancel(true)
            return super.cancel(mayInterruptIfRunning)
        }
    }

    private class PeriodicTask<V>: BaseTask<V> {
        private val period: Duration
        private val rescheduler: ExecutorService

        @Volatile
        private var nextMark: ComparableTimeMark

        @Volatile
        private var sleepTask: FutureTask<Unit>? = null

        constructor(
            r: Runnable,
            result: V,
            triggerTime: ComparableTimeMark,
            period: Duration,
            sequenceNumber: Long,
            rescheduler: ExecutorService,
        ) : super(r, result, sequenceNumber, triggerTime) {
            this.period = period
            this.nextMark = mark
            this.rescheduler = rescheduler
        }

        override fun run() {
            if (isCancelled) return

            val sleeper = FutureTask(Sleeping(nextMark), Unit)
            sleepTask = sleeper
            sleeper.run()
            sleepTask = null

            if (isCancelled || sleeper.isCancelled) return

            val executed = runAndReset()
            if (!executed || isCancelled || rescheduler.isShutdown) return

            // fixed-delay: 실행 완료 시점 기준으로 다음 시각 계산
            nextMark = mark + mark.elapsedNow() + period
            try {
                rescheduler.execute(this)
            } catch (_: RejectedExecutionException) {
                cancel(false)
            }
        }

        override fun isPeriodic() = true

        override val expectedMark get() = nextMark

        override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
            sleepTask?.cancel(true)
            return super.cancel(mayInterruptIfRunning)
        }
    }

}