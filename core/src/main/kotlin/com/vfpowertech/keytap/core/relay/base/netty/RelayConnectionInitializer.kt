package com.vfpowertech.keytap.core.relay.base.netty

import com.vfpowertech.keytap.core.tls.TrustAllTrustManager
import com.vfpowertech.keytap.core.relay.base.RelayConnectionEvent
import io.netty.channel.ChannelInitializer
import io.netty.channel.socket.SocketChannel
import io.netty.handler.ssl.SslHandler
import rx.Observer
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine

/**
 * Initializes the netty pipeline for the relay server.
 *
 * Pipeline:
 *
 * server <-> SslHandler -> ServerMessageHandler
 *                       <- ClientMessageHandler <- client
 */
class RelayConnectionInitializer(private val observer: Observer<in RelayConnectionEvent>) : ChannelInitializer<SocketChannel>() {
    private fun getSSLEngine(): SSLEngine {
        //FIXME
        val sslContext = SSLContext.getInstance("TLSv1.2")
        sslContext.init(null, arrayOf(TrustAllTrustManager()), null)

        val engine = sslContext.createSSLEngine()
        engine.useClientMode = true
        return engine
    }

    override fun initChannel(channel: SocketChannel) {
        val pipeline = channel.pipeline()
        pipeline.addLast(SslHandler(getSSLEngine()))
        pipeline.addLast(ServerMessageHandler(observer))
        pipeline.addLast(ClientMessageHandler())
    }
}

