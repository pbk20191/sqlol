package org.example.sqlol

//import org.springframework.boot.autoconfigure.integration.IntegrationProperties
//import org.springframework.boot.autoconfigure.rsocket.RSocketMessageHandlerCustomizer
//import org.springframework.boot.autoconfigure.web.reactive.ReactiveWebServerFactoryAutoConfiguration
//import org.springframework.boot.autoconfigure.websocket.reactive.WebSocketReactiveAutoConfiguration
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.asPublisher
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.boot.rsocket.autoconfigure.RSocketMessageHandlerCustomizer
import org.springframework.boot.rsocket.server.RSocketServerCustomizer
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.reactive.socket.WebSocketMessage
import org.springframework.web.reactive.socket.WebSocketSession

//class OioDomainSocketChannel(val socket: Socket, parent: Channel?): OioByteStreamChannel(parent), DomainSocketChannel {
//
//}
//
//class OioServerDomainSocketChannel(val socket: ServerSocket, parent: Channel?): AbstractOioMessageChannel(parent), ServerDomainSocketChannel {
//    override fun localAddress0(): SocketAddress? {
//        return SocketUtils.localSocketAddress(socket)
//    }
//
//    override fun fd(): FileDescriptor? {
//        TODO("Not yet implemented")
//    }
//}

//class FoooGroup(val service: LoomScheduledExecutorService):
//    OioEventLoopGroup(0, service)
//
//{
//
//
//
//}
//

//
//class VirtualLoopResources: LoopResources {
//
//    val executor = LoomScheduledExecutorService()
//    val group = OioEventLoopGroup(0, executor)
//
//
//
//    override fun onServer(useNative: Boolean): EventLoopGroup {
//
//        io.netty.util.concurrent.FastThreadLocalThread::ofVirtual
//        return group
//    }
//
//    override fun <CHANNEL : Channel> onChannel(channelType: Class<CHANNEL>, group: EventLoopGroup): CHANNEL {
//                if (channelType == SocketChannel::class.java) {
//            return (OioSocketChannel()) as CHANNEL
//        } else if (channelType == ServerSocketChannel::class.java) {
//            return (OioServerSocketChannel()) as CHANNEL
//        } else if (channelType == DatagramChannel::class.java) {
//            return (OioDatagramChannel()) as CHANNEL
//        } else if (channelType == DomainSocketChannel::class.java) {
//            return (NioDomainSocketChannel()) as CHANNEL
//        } else if (channelType == ServerDomainSocketChannel::class.java) {
//            return (NioServerDomainSocketChannel()) as CHANNEL
//        } else {
//            throw IllegalArgumentException("Unsupported channel type: " + channelType.getSimpleName())
//        }
//    }
//
//    override fun <CHANNEL : Channel> onChannelClass(
//        channelType: Class<CHANNEL>,
//        group: EventLoopGroup
//    ): Class<out CHANNEL> {
//        if (channelType == SocketChannel::class.java) {
//            return OioSocketChannel::class.java as Class<out CHANNEL>
//        } else if (channelType == ServerSocketChannel::class.java) {
//            return OioServerSocketChannel::class.java as Class<out CHANNEL>
//        } else if (channelType == DatagramChannel::class.java) {
//            return OioDatagramChannel::class.java as Class<out CHANNEL>
//        } else {
//            throw IllegalArgumentException("Unsupported channel type: " + channelType.getSimpleName())
//        }
//    }
////    override fun <CHANNEL : Channel?> getChannel(channelClass: Class<CHANNEL?>): CHANNEL? {
////        if (channelClass == SocketChannel::class.java) {
////            return (NioSocketChannel()) as CHANNEL
////        } else if (channelClass == ServerSocketChannel::class.java) {
////            return (NioServerSocketChannel()) as CHANNEL
////        } else if (channelClass == DatagramChannel::class.java) {
////            return (NioDatagramChannel()) as CHANNEL
////        } else if (channelClass == DomainSocketChannel::class.java) {
////            return (NioDomainSocketChannel()) as CHANNEL
////        } else if (channelClass == ServerDomainSocketChannel::class.java) {
////            return (NioServerDomainSocketChannel()) as CHANNEL
////        } else {
////            throw IllegalArgumentException("Unsupported channel type: " + channelClass.getSimpleName())
////        }
////    }
////
////    override fun <CHANNEL : Channel?> getChannelClass(channelClass: Class<CHANNEL?>): Class<out CHANNEL?> {
////        if (channelClass == SocketChannel::class.java) {
////            return NioSocketChannel::class.java
////        } else if (channelClass == ServerSocketChannel::class.java) {
////            return NioServerSocketChannel::class.java
////        } else if (channelClass == DatagramChannel::class.java) {
////            return NioDatagramChannel::class.java
////        } else {
////            throw IllegalArgumentException("Unsupported channel type: " + channelClass.getSimpleName())
////        }
////    }
//
//    override fun disposeLater(): Mono<Void> {
//        group.shutdown()
//        return super.disposeLater()
//    }
//}

@RestController
class MyController {

    @GetMapping("/test")
   suspend fun test(): String {
        return "Hello world"
    }


    @MessageMapping("/msg")
    suspend fun asdfsd(session: WebSocketSession) {
        session.receive().asFlow().collect {
            DefaultDataBufferFactory.sharedInstance
            val message = WebSocketMessage(
                WebSocketMessage.Type.TEXT,
                it.payload
            )
            session.send(
                flowOf(WebSocketMessage(WebSocketMessage.Type.TEXT, it.payload)).asPublisher()
            ).awaitSingleOrNull()
        }
    }

    fun asdf(): RSocketServerCustomizer {
        return  RSocketServerCustomizer {

        }
    }

    fun a222() = RSocketMessageHandlerCustomizer {


    }

//    fun asdf9() {
//        ReactiveWebServerFactoryAsutoConfiguration::class
//        IntegrationProperties.RSocket::class
//        NioEventLoopGroup
////        RSocketServerAutoConfiguration
//        WebSocketReactiveAutoConfiguration::class
////        WebSocketHandler
//    }

}