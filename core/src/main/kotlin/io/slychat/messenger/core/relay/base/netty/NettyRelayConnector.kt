package io.slychat.messenger.core.relay.base.netty

import io.netty.bootstrap.Bootstrap
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelOption
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioSocketChannel
import io.slychat.messenger.core.relay.base.RelayConnectionEstablished
import io.slychat.messenger.core.relay.base.RelayConnectionEvent
import io.slychat.messenger.core.relay.base.RelayConnector
import nl.komponents.kovenant.deferred
import rx.Observable
import java.net.InetSocketAddress

class NettyRelayConnector : RelayConnector {
    override fun connect(address: InetSocketAddress): Observable<RelayConnectionEvent> =
        Observable.create({ subscriber ->
            val sslHandshakeComplete = deferred<Boolean, Exception>()

            val eventLoopGroup = NioEventLoopGroup()

            val bootstrap = Bootstrap()
                .group(eventLoopGroup)
                .channel(NioSocketChannel::class.java)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .handler(RelayConnectionInitializer(subscriber, sslHandshakeComplete))

            val channelFuture = bootstrap.connect(address)
            channelFuture.addListener(ChannelFutureListener { cf ->
                if (!cf.isSuccess) {
                    subscriber.onError(cf.cause())
                    eventLoopGroup.shutdownGracefully()
                }
                else {
                    //if this fails, exceptionCaught is called, which'll trigger subscriber.onError
                    val channel = cf.channel()

                    channel.closeFuture().addListener {
                        eventLoopGroup.shutdownGracefully()
                    }

                    sslHandshakeComplete.promise success { successful ->
                        if (successful)
                            subscriber.onNext(RelayConnectionEstablished(NettyRelayConnection(channel)))
                    }
                }
            })
        })
}