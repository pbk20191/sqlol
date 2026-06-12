package org.example.demo1webfl

import io.netty.channel.EventLoopGroup
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.nio.NioIoHandler
import io.netty.util.concurrent.FastThreadLocalThread
import io.netty.util.concurrent.Future
import kotlinx.coroutines.Dispatchers
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration
import org.springframework.boot.reactor.netty.NettyServerCustomizer
import org.springframework.boot.reactor.netty.autoconfigure.ReactorNettyProperties
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.ReactorResourceFactory
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.SchedulingConfigurer
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler
import org.springframework.scheduling.config.TaskSchedulerRouter
import org.springframework.web.server.CoWebFilter
import org.springframework.web.server.WebFilter
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import reactor.netty.FutureMono
import reactor.netty.resources.ConnectionProvider
import reactor.netty.resources.LoopResources
import java.time.Duration
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

@EnableAsync
@EnableScheduling
@Configuration
class ThreadConfiguration {
    @Bean(
        value = [
            "virtualExecutor",
            "clientOutboundChannelExecutor", "brokerChannelExecutor", "clientInboundChannelExecutor",

        ],
        destroyMethod = "shutdown"
    )
    fun virtualExecutor(): LoomScheduledExecutorService = LoomScheduledExecutorService()


    @Bean
    fun configureScheduler(
        @Qualifier(TaskSchedulerRouter.DEFAULT_TASK_SCHEDULER_BEAN_NAME)
        service: TaskScheduler,
    ) = SchedulingConfigurer {
        it.setScheduler(service)
    }

    @Bean(destroyMethod = "dispose") fun reactorScheduler(
        service: LoomScheduledExecutorService,
    ) = Schedulers.fromExecutorService(service)


    @Bean(value = [
        TaskSchedulerRouter.DEFAULT_TASK_SCHEDULER_BEAN_NAME, TaskExecutionAutoConfiguration.APPLICATION_TASK_EXECUTOR_BEAN_NAME, ConfigurableApplicationContext.BOOTSTRAP_EXECUTOR_BEAN_NAME,
        "messageBrokerTaskScheduler", "messageBrokerSockJsTaskScheduler"
    ])
    fun taskScheduler(
        service: LoomScheduledExecutorService,
    ) = ConcurrentTaskScheduler(service)

    class AdaptedService(
        val service: ScheduledExecutorService
    ): ScheduledExecutorService by service {

        override fun execute(p0: Runnable) {
            service.execute{
                FastThreadLocalThread.runWithFastThreadLocal(p0)
            }
        }

        override fun close() {

            service.close()
        }

    }

    class VirtualNioLoopResources(
        service: ScheduledExecutorService
    ): LoopResources {

        val eventLoop = MultiThreadIoEventLoopGroup(
            AdaptedService(service),
            NioIoHandler.newFactory(),

            )

        override fun onServer(useNative: Boolean): EventLoopGroup {
            return eventLoop
        }

        override fun disposeLater(quietPeriod: Duration, timeout: Duration): Mono<Void> {
            val t = FutureMono.from( eventLoop.shutdownGracefully(quietPeriod.toMillis(), timeout.toMillis(), TimeUnit.MILLISECONDS) as Future<Void>)
            return t
        }

    }

    @Bean
    fun reactorResourceFactory(
        configurationProperties: ReactorNettyProperties,
        scheduler: Scheduler,
        service: LoomScheduledExecutorService,
    ): ReactorResourceFactory {
        val reactorResourceFactory = ReactorResourceFactory()
        if (configurationProperties.shutdownQuietPeriod != null) {

            reactorResourceFactory.setShutdownQuietPeriod(configurationProperties.shutdownQuietPeriod!!)
        }
        reactorResourceFactory.isUseGlobalResources = false
        reactorResourceFactory.setLoopResourcesSupplier {
            VirtualNioLoopResources(service)
        }
        Dispatchers.Main
//        AppContext.getAppContext().put(SwingWorker::class, service)
//        Toolkit.getDefaultToolkit()
        reactorResourceFactory.setConnectionProviderSupplier {
            ConnectionProvider.builder("webflux")
                .pendingAcquireTimeout(Duration.ofMillis(ConnectionProvider.DEFAULT_POOL_ACQUIRE_TIMEOUT))
                .maxConnectionPools(500)
                .evictInBackground(Duration.ofSeconds(1), scheduler)
                .pendingAcquireTimer{ a, b ->
                    scheduler.schedule(a,b.toNanos(), TimeUnit.NANOSECONDS)
                }
                .build()
        }

        return reactorResourceFactory
    }

    @Bean
    fun handle(
        service: LoomScheduledExecutorService,
        scheduler: Scheduler,
    ) = WebFilter { exchange, chain ->
        val attributes = (exchange.attributes[CoWebFilter.COROUTINE_CONTEXT_ATTRIBUTE] as? CoroutineContext)?.plus(
            service.dispatcher
        ) ?: service.dispatcher
        exchange.attributes[CoWebFilter.COROUTINE_CONTEXT_ATTRIBUTE] = attributes

        chain.filter(exchange)
//            .contextWrite(Context.of())
            .subscribeOn(scheduler)
//            .publishOn(scheduler)
    }


    @Bean
    fun serverCustomizer() = NettyServerCustomizer {
        it
    }
}