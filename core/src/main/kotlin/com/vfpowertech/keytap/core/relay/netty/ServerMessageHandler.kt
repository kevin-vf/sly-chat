package com.vfpowertech.keytap.core.relay.netty

import com.vfpowertech.keytap.core.relay.HEADER_SIZE
import com.vfpowertech.keytap.core.relay.Header
import com.vfpowertech.keytap.core.relay.RelayConnectionLost
import com.vfpowertech.keytap.core.relay.RelayMessage
import com.vfpowertech.keytap.core.relay.ServerMessage
import com.vfpowertech.keytap.core.relay.headerFromBytes
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.ByteToMessageDecoder
import rx.Observer

/** Handles converting received server messages into message instances. */
class ServerMessageHandler(private val observer: Observer<in ServerMessage>) : ByteToMessageDecoder() {
    private var lastHeader: Header? = null

    override fun decode(ctx: ChannelHandlerContext, `in`: ByteBuf, out: MutableList<Any>) {
        while (`in`.isReadable) {
            var header = lastHeader

            if (header == null) {
                val available = `in`.readableBytes()

                if (available < HEADER_SIZE)
                    break

                val buffer = ByteArray(HEADER_SIZE)
                `in`.readBytes(buffer)
                header = headerFromBytes(buffer)
                if (header.contentLength == 0) {
                    observer.onNext(RelayMessage(header, ByteArray(0)))
                    continue
                }

                lastHeader = header
            }

            val available = `in`.readableBytes()
            if (available < header.contentLength)
                break

            val content = ByteArray(header.contentLength)
            `in`.readBytes(content)

            lastHeader = null
            observer.onNext(RelayMessage(header, content))
        }
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        observer.onError(cause)
        ctx.close()
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        //TODO should detect disconnection (so we know whether or not to reconnect)
        //if the server disconnected (wasn't requested), use onError
        observer.onNext(RelayConnectionLost())
        observer.onCompleted()
    }
}