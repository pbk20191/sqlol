package org.example.demo1webfl

import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.nio.NioIoHandler
import kotlinx.coroutines.Dispatchers
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.core.task.support.ExecutorServiceAdapter
import org.springframework.core.task.support.TaskExecutorAdapter
import org.springframework.web.reactive.config.EnableWebFlux
import java.nio.channels.spi.AsynchronousChannelProvider
import java.util.concurrent.ThreadFactory

//@EnableR2dbcRepositories
@EnableWebFlux
@SpringBootApplication
class Demo1webflApplication {

    class VFactory @JvmOverloads constructor(
        private val factory: ThreadFactory = Thread.ofVirtual().factory()
    ): ThreadFactory by factory

    companion object {
        @JvmStatic
        fun main(vararg args: String) {
            System.setProperty("jdk.pollerMode", "2")
            System.setProperty("reactor.schedulers.defaultBoundedElasticOnVirtualThreads", "true")
            System.setProperty("java.awt.headless", "true")
            runApplication<Demo1webflApplication>(*args) {

                this.setWebApplicationType(WebApplicationType.REACTIVE)
                this.setHeadless(true)
                this.isKeepAlive = false

                Dispatchers.Main
                println(CFRunLoopRef)
                println(LoomSupport.__MH_OF_VIRTUAL)
            }
        }


        fun asdf() {
            ExecutorServiceAdapter::class
            TaskExecutorAdapter::class
//            val  = LoomScheduledExecutorService()
            val dispatcher = LoomCoroutineDispatcher()
            val foo = dispatcher.scheduledExecutorService
//            SchedulingTaskExecutor
            MultiThreadIoEventLoopGroup(
                0, foo, NioIoHandler.newFactory()
            )
        }
    }


}


