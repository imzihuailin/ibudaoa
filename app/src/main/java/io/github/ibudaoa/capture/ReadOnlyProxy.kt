package io.github.ibudaoa.capture

import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.Unpooled
import io.netty.buffer.UnpooledByteBufAllocator
import io.netty.channel.*
import io.netty.channel.group.DefaultChannelGroup
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.*
import io.netty.handler.ssl.SslHandler
import io.netty.handler.timeout.IdleStateEvent
import io.netty.handler.timeout.IdleStateHandler
import io.netty.util.ReferenceCountUtil
import io.netty.util.concurrent.DefaultThreadFactory
import io.netty.util.concurrent.GlobalEventExecutor
import java.io.Closeable
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import javax.net.ssl.SSLContext

/** Loopback-only NIO CONNECT/TLS/HTTP1 proxy; every request is filtered before upstream IO. */
internal class ReadOnlyProxy(
    private val tls:SSLContext,
    private val onBeforeRun:(ByteArray)->Unit,
    private val onBlocked:(BlockedRequestKind)->Unit,
    private val onProblem:(CaptureProblem)->Unit,
    private val onAccountProfile:(ByteArray)->Unit={},
    private val accountCheckOnly:Boolean=false,
    private val onAccountRead:(AccountReadResult)->Unit={},
    private val onAcceptedAccount:((LocalRequest)->Unit)?=null,
    private val accountMaintenance:Boolean=false,
    private val onAccountProtocol:((LocalRequest,ByteArray)->Unit)?=null,
    private val onTrace:(CaptureTrace)->Unit={},
    private val upstream:CaptureHttpTransport=CaptureHttpTransport(onTrace),
    private val probes:CaptureHttpTransport=CaptureHttpTransport(onTrace),
):Closeable {
    companion object {
        init { io.netty.util.internal.logging.InternalLoggerFactory.setDefaultFactory(io.netty.util.internal.logging.JdkLoggerFactory.INSTANCE) }
    }
    private val acceptor=NioEventLoopGroup(1,DefaultThreadFactory("capture-accept",true))
    private val io=NioEventLoopGroup(1,DefaultThreadFactory("capture-io",true))
    private val channels=DefaultChannelGroup(GlobalEventExecutor.INSTANCE)
    private val connections=AtomicInteger()
    private val workers=ThreadPoolExecutor(4,4,30,TimeUnit.SECONDS,ArrayBlockingQueue(8),DefaultThreadFactory("capture-http",true))
    private val responses=CaptureResponseQueue()
    private val accountProbeClaimed=AtomicBoolean(false)
    @Volatile private var closed=false
    @Volatile private var accepting=true
    private val server:Channel
    val port:Int get()=(server.localAddress() as java.net.InetSocketAddress).port

    init {
        try {
            server=ServerBootstrap().group(acceptor,io).channelFactory(ChannelFactory { NioServerSocketChannel() })
                .childOption(ChannelOption.ALLOCATOR,UnpooledByteBufAllocator.DEFAULT)
                .childOption(ChannelOption.TCP_NODELAY,true)
                .childHandler(object:ChannelInitializer<SocketChannel>() {
                    // This initializer is intentionally reused for every accepted connection.
                    // Avoid depending on R8 preserving an inherited @Sharable annotation.
                    override fun isSharable()=true
                    override fun initChannel(ch:SocketChannel) {
                        onTrace(CaptureTrace(CaptureStage.CONNECT))
                        if(connections.incrementAndGet()>32){connections.decrementAndGet();ch.close();return}
                        ch.closeFuture().addListener { connections.decrementAndGet() }
                        channels.add(ch)
                        val state=ClientState()
                        ch.pipeline().addLast("idle",IdleStateHandler(15,0,0))
                            .addLast("decoder",StrictDecoder(true)).addLast("encoder",HttpResponseEncoder())
                            .addLast("policy",Headers(state)).addLast("aggregate",Aggregator())
                            .addLast("request",Requests(state))
                    }
                }).bind("127.0.0.1",0).syncUninterruptibly().channel()
        } catch(e:Throwable) {
            acceptor.shutdownGracefully(0,1,TimeUnit.SECONDS);io.shutdownGracefully(0,1,TimeUnit.SECONDS)
            workers.shutdownNow();responses.cancel();upstream.close();probes.close();throw e
        }
    }
    fun serve(){server.closeFuture().syncUninterruptibly()}

    private class StrictDecoder(single:Boolean):HttpRequestDecoder(HttpDecoderConfig()
        .setMaxInitialLineLength(8192).setMaxHeaderSize(32768).setMaxChunkSize(8192)
        .setAllowDuplicateContentLengths(false).setValidateHeaders(true)) {
        init { setSingleDecode(single) }
        override fun handleTransferEncodingChunkedWithContentLength(message:HttpMessage) {
            throw CaptureProtocolException(CaptureFailure.AMBIGUOUS_LENGTH)
        }
    }
    private data class Owned(val request:LocalRequest,val keepAlive:Boolean,val version:HttpVersion)
    private class ClientState {
        var tunnel=false
        var directHttp=false
        var stage=CaptureStage.CONNECT
        var working=false
        val queued=ArrayDeque<Owned>()
    }
    private inner class Headers(private val state:ClientState):ChannelInboundHandlerAdapter() {
        override fun channelRead(ctx:ChannelHandlerContext,msg:Any) {
            var drainBufferedBody=false
            if(msg is HttpRequest) {
                state.stage=if(!state.tunnel && msg.method()==HttpMethod.CONNECT)CaptureStage.CONNECT else CaptureStage.HEADERS
                if(!msg.decoderResult().isSuccess) {
                    val cause=msg.decoderResult().cause()
                    val reason=(cause as? CaptureProtocolException)?.reason ?: if(cause is io.netty.handler.codec.TooLongFrameException)
                        CaptureFailure.HEADERS else CaptureFailure.REQUEST_LINE
                    reject(ctx,state.stage,reason,HttpResponseStatus.BAD_REQUEST);ReferenceCountUtil.release(msg);return
                }
                val lengths=msg.headers().getAll(HttpHeaderNames.CONTENT_LENGTH)
                val encodings=msg.headers().getAll(HttpHeaderNames.TRANSFER_ENCODING)
                val expect=msg.headers().get(HttpHeaderNames.EXPECT)
                if(lengths.any { it.toLongOrNull()?.let { length->length>HttpRequestReader.MAX_BODY }==true }) {
                    reject(ctx,CaptureStage.BODY,CaptureFailure.BODY_LIMIT,HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE);return
                }
                val framingInvalid=lengths.size>1 || lengths.any { it.toLongOrNull()?.let { n->n<0 || n>HttpRequestReader.MAX_BODY }!=false } ||
                    encodings.size>1 || encodings.any { !it.equals("chunked",true) } || lengths.isNotEmpty() && encodings.isNotEmpty()
                if(framingInvalid) {
                    reject(ctx,state.stage,CaptureFailure.AMBIGUOUS_LENGTH,HttpResponseStatus.BAD_REQUEST);return
                }
                val hosts=msg.headers().getAll(HttpHeaderNames.HOST)
                val connect=!state.tunnel && msg.method()==HttpMethod.CONNECT
                val path=if(connect)null else ProxyRequestTarget.path(msg.uri(),hosts,state.tunnel)
                val failure=when {
                    connect && state.directHttp -> CaptureFailure.BLOCKED_METHOD
                    connect && !ProxyRequestTarget.connect(msg.uri()) -> CaptureFailure.BLOCKED_HOST
                    connect && (lengths.any {it!="0"} || encodings.isNotEmpty()) -> CaptureFailure.BLOCKED_CONNECT_BODY
                    connect -> null
                    path==null -> CaptureFailure.BLOCKED_HOST
                    msg.method()!=HttpMethod.POST -> CaptureFailure.BLOCKED_METHOD
                    !allowed(msg.method().name(),path) -> CaptureFailure.BLOCKED_PATH
                    else -> null
                }
                if(failure!=null) {
                    onBlocked(ReadOnlyPolicy.blockedKind(path ?: ""))
                    reject(ctx,state.stage,failure,HttpResponseStatus.FORBIDDEN);return
                }
                if(expect!=null && (connect || !expect.equals("100-continue",true))) {
                    reject(ctx,state.stage,CaptureFailure.BODY_ENCODING,HttpResponseStatus.EXPECTATION_FAILED);return
                }
                if(path!=null)msg.setUri(path) // Upstream always uses the pinned HTTPS host, never the supplied URL.
                if(path!=null && !state.tunnel && !state.directHttp) {
                    state.directHttp=true
                    (ctx.pipeline().get("decoder") as StrictDecoder).setSingleDecode(false)
                    drainBufferedBody=true
                }
                onTrace(CaptureTrace(state.stage))
                if(!connect)state.stage=CaptureStage.BODY
                // The aggregator handles 100-continue only after the request passed the allowlist.
            }
            ctx.fireChannelRead(msg)
            if(drainBufferedBody)ctx.executor().execute {
                if(ctx.channel().isActive)ctx.pipeline().fireChannelRead(Unpooled.EMPTY_BUFFER)
            }
        }
    }
    private inner class Aggregator:HttpObjectAggregator(HttpRequestReader.MAX_BODY) {
        override fun handleOversizedMessage(ctx:ChannelHandlerContext,oversized:HttpMessage) {
            reject(ctx,CaptureStage.BODY,CaptureFailure.BODY_LIMIT,HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE)
        }
    }
    private inner class Requests(private val state:ClientState):SimpleChannelInboundHandler<FullHttpRequest>(FullHttpRequest::class.java) {
        override fun channelRead0(ctx:ChannelHandlerContext,msg:FullHttpRequest) {
            if(!msg.decoderResult().isSuccess) {reject(ctx,CaptureStage.BODY,CaptureFailure.TRUNCATED,HttpResponseStatus.BAD_REQUEST);return}
            if(!state.tunnel && msg.method()==HttpMethod.CONNECT) {
                state.tunnel=true;state.stage=CaptureStage.TLS
                val response=DefaultFullHttpResponse(HttpVersion.HTTP_1_1,HttpResponseStatus.OK)
                ctx.writeAndFlush(response).addListener { future ->
                    if(!future.isSuccess){ctx.close();return@addListener}
                    val engine=tls.createSSLEngine().apply {
                        useClientMode=false
                        enabledProtocols=supportedProtocols.filter { it=="TLSv1.2" || it=="TLSv1.3" }.toTypedArray()
                        sslParameters=sslParameters.apply { applicationProtocols=arrayOf("http/1.1") }
                    }
                    val ssl=SslHandler(engine).apply { handshakeTimeoutMillis=10_000 }
                    ctx.pipeline().replace("decoder","tls",ssl)
                    ctx.pipeline().addAfter("tls","decoder",StrictDecoder(false))
                    ssl.handshakeFuture().addListener { handshake ->
                        if(handshake.isSuccess){state.stage=CaptureStage.HEADERS;onTrace(CaptureTrace(CaptureStage.TLS))}
                        else {onTrace(CaptureTrace(CaptureStage.TLS,CaptureFailure.TLS));if(accepting)onProblem(CaptureProblem.TLS);ctx.close()}
                    }
                }
                return
            }
            val body=ByteArray(msg.content().readableBytes())
            msg.content().readBytes(body)
            msg.content().setZero(0,msg.content().capacity())
            val request=LocalRequest(msg.method().name(),msg.uri(),msg.headers().map { it.key to it.value },body)
            // Trailer values never override routing, authentication, signature or content headers.
            onTrace(CaptureTrace(CaptureStage.BODY))
            state.stage=CaptureStage.HEADERS
            if(!accepting || closed){body.fill(0);ctx.close();return}
            if(state.queued.size>=4){body.fill(0);reject(ctx,CaptureStage.BODY,CaptureFailure.CAPACITY,HttpResponseStatus.SERVICE_UNAVAILABLE);return}
            state.queued.add(Owned(request,HttpUtil.isKeepAlive(msg),msg.protocolVersion()))
            ctx.channel().config().isAutoRead=false
            dispatch(ctx,state)
        }
        override fun channelInactive(ctx:ChannelHandlerContext) {
            if(state.stage==CaptureStage.BODY && accepting && !closed)onTrace(CaptureTrace(CaptureStage.BODY,CaptureFailure.TRUNCATED))
            state.queued.forEach { it.request.body.fill(0) };state.queued.clear();ctx.fireChannelInactive()
        }
        override fun userEventTriggered(ctx:ChannelHandlerContext,event:Any) {
            if(event is IdleStateEvent && !state.working) {
                onTrace(CaptureTrace(state.stage,if(state.stage==CaptureStage.BODY)CaptureFailure.TRUNCATED else CaptureFailure.IDLE));ctx.close()
            } else ctx.fireUserEventTriggered(event)
        }
        override fun exceptionCaught(ctx:ChannelHandlerContext,cause:Throwable) {
            val reason=(cause as? CaptureProtocolException)?.reason ?: when(cause) {
                is javax.net.ssl.SSLException -> CaptureFailure.TLS
                is io.netty.handler.codec.TooLongFrameException -> CaptureFailure.HEADERS
                is io.netty.handler.codec.DecoderException -> CaptureFailure.TRUNCATED
                else -> CaptureFailure.NETWORK
            }
            onTrace(CaptureTrace(state.stage,reason))
            if(accepting && !closed && reason!=CaptureFailure.NETWORK)onProblem(if(reason==CaptureFailure.TLS)CaptureProblem.TLS else CaptureProblem.REQUEST_FORMAT)
            ctx.close()
        }
    }
    private fun allowed(method:String,target:String):Boolean {
        val maintenance=accountMaintenance && method=="POST" && target in setOf(ReadOnlyPolicy.BEFORE_RUN,AccountProtocol.REFRESH)
        return (ReadOnlyPolicy.allows(method,target)||maintenance) && (!accountCheckOnly || target==ReadOnlyPolicy.USER_PROFILE || target==ReadOnlyPolicy.BOOTSTRAP_IP || maintenance)
    }
    private fun reject(ctx:ChannelHandlerContext,stage:CaptureStage,reason:CaptureFailure,status:HttpResponseStatus) {
        onTrace(CaptureTrace(stage,reason))
        if(accepting && !closed) when(reason) {
            CaptureFailure.NETWORK,CaptureFailure.TIMEOUT,CaptureFailure.CAPACITY -> onProblem(CaptureProblem.NETWORK)
            CaptureFailure.RESPONSE_ENCODING,CaptureFailure.RESPONSE_LIMIT -> onProblem(CaptureProblem.INVALID_RESPONSE)
            CaptureFailure.BLOCKED,CaptureFailure.BLOCKED_HOST,CaptureFailure.BLOCKED_METHOD,
            CaptureFailure.BLOCKED_PATH,CaptureFailure.BLOCKED_CONNECT_BODY,CaptureFailure.CANCELLED,CaptureFailure.IDLE -> Unit
            else -> onProblem(CaptureProblem.REQUEST_FORMAT)
        }
        val body="{\"status\":0,\"info\":\"只读诊断已阻止该请求\"}".toByteArray()
        val response=DefaultFullHttpResponse(HttpVersion.HTTP_1_1,status,Unpooled.wrappedBuffer(body))
        HttpUtil.setContentLength(response,body.size.toLong());HttpUtil.setKeepAlive(response,false)
        ctx.channel().config().isAutoRead=false
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE)
    }
    private fun dispatch(ctx:ChannelHandlerContext,state:ClientState) {
        if(state.working || state.queued.isEmpty() || !ctx.channel().isActive)return
        val owned=state.queued.removeFirst();state.working=true
        try { workers.execute work@{
            try {
                if(closed || !accepting)return@work
                val reply=upstream.execute(owned.request,accountMaintenance)
                if(!closed && accepting)acceptResponse(owned.request,reply)
                try { ctx.executor().execute send@{
                    if(!ctx.channel().isActive || closed){reply.body.fill(0);return@send}
                    val bytes=Unpooled.wrappedBuffer(reply.body)
                    val wipe=bytes.retainedDuplicate()
                    val response=DefaultFullHttpResponse(owned.version,HttpResponseStatus.valueOf(reply.status),bytes)
                    try {
                        reply.headers.filter { it.first.lowercase() !in CaptureHttpTransport.hopHeaders(reply.headers) }.forEach { (k,v) ->
                            if(v.none { it=='\r'||it=='\n' })response.headers().add(k,v)
                        }
                        HttpUtil.setContentLength(response,reply.body.size.toLong());HttpUtil.setKeepAlive(response,owned.keepAlive)
                        ctx.writeAndFlush(response).addListener { future ->
                            wipe.setZero(0,wipe.capacity());wipe.release()
                            state.working=false
                            if(!future.isSuccess || !owned.keepAlive)ctx.close()
                            else {if(state.queued.isEmpty())ctx.channel().config().isAutoRead=true;dispatch(ctx,state)}
                        }
                    } catch(_:Exception) {
                        wipe.setZero(0,wipe.capacity());wipe.release();ReferenceCountUtil.release(response)
                        onTrace(CaptureTrace(CaptureStage.RESPONSE,CaptureFailure.HEADERS));ctx.close()
                    }
                } } catch(e:Exception) {reply.body.fill(0);throw e}
            } catch(e:Exception) {
                if(accepting && !closed){ctx.executor().execute {
                    reject(ctx,if(e is CaptureProtocolException)CaptureStage.RESPONSE else CaptureStage.UPSTREAM,
                        if(e is CaptureProtocolException)e.reason else if(e is java.net.SocketTimeoutException)CaptureFailure.TIMEOUT else CaptureFailure.NETWORK,HttpResponseStatus.BAD_GATEWAY)
                }}
            } finally {owned.request.body.fill(0)}
        }} catch(_:Exception) {owned.request.body.fill(0);state.working=false;reject(ctx,CaptureStage.UPSTREAM,CaptureFailure.CAPACITY,HttpResponseStatus.SERVICE_UNAVAILABLE)}
    }
    private fun acceptResponse(request:LocalRequest,reply:CaptureHttpReply) {
        if(request.target !in setOf(ReadOnlyPolicy.BEFORE_RUN,ReadOnlyPolicy.USER_PROFILE,AccountProtocol.REFRESH))return
        val owned=request.copy(body=request.body.clone());val raw=reply.body.clone()
        if(!responses.submit({if(!closed)try{processResponse(owned,raw,reply.status,reply.encoding)}catch(error:Exception){
            onTrace(CaptureTrace(CaptureStage.RESPONSE,(error as? CaptureProtocolException)?.reason ?: CaptureFailure.RESPONSE_ENCODING));if(!closed)onProblem(CaptureProblem.INVALID_RESPONSE)
        }},{owned.body.fill(0);raw.fill(0)})) {
            onTrace(CaptureTrace(CaptureStage.RESPONSE,if(closed || !accepting)CaptureFailure.CANCELLED else CaptureFailure.CAPACITY))
            if(!closed && accepting)onProblem(CaptureProblem.NETWORK)
        }
    }
    private fun processResponse(request: LocalRequest, raw: ByteArray, status: Int, encoding: String?) {
                if (request.target in setOf(ReadOnlyPolicy.BEFORE_RUN, ReadOnlyPolicy.USER_PROFILE,AccountProtocol.REFRESH) && status == 200 && !closed) {
                    val bytes = when (encoding?.lowercase()) {
                        null, "identity" -> raw
                        "gzip" -> if (request.target == ReadOnlyPolicy.USER_PROFILE)
                            runCatching { GZIPInputStream(raw.inputStream()).use { CaptureHttpTransport.bounded(it) } }.getOrNull()
                        else GZIPInputStream(raw.inputStream()).use { CaptureHttpTransport.bounded(it) }
                        else -> if (request.target == ReadOnlyPolicy.USER_PROFILE) null else throw CaptureProtocolException()
                    }
                    if (bytes != null) try {
                        if(accountMaintenance && request.target in setOf(ReadOnlyPolicy.BEFORE_RUN,AccountProtocol.REFRESH)) {
                            onAccountProtocol?.invoke(request,bytes)
                            if (request.target == ReadOnlyPolicy.BEFORE_RUN) onBeforeRun(bytes)
                        } else if (request.target == ReadOnlyPolicy.BEFORE_RUN) onBeforeRun(bytes) else {
                            if (!accountCheckOnly) onAccountProfile(bytes)
                            if (accountCheckOnly && accountProbeClaimed.compareAndSet(false,true) && !closed) {
                                onAccountProfile(bytes)
                                val result=AccountReadProbe.checkWithRetry(request,bytes,
                                    nowSeconds={ System.currentTimeMillis()/1000 }, nonce={ 100_000+SecureRandom().nextInt(900_000) },
                                    isCancelled={closed}, onAccepted={ accepted ->
                                        if(!closed) onAcceptedAccount?.invoke(accepted)
                                    }) { body -> probeProfile(body,request) }
                                if (!closed) onAccountRead(result)
                            }
                        }
                    } finally { if (bytes !== raw) bytes.fill(0) }
                    if (bytes == null && accountCheckOnly && request.target == ReadOnlyPolicy.USER_PROFILE &&
                        accountProbeClaimed.compareAndSet(false, true) && !closed)
                        onAccountRead(AccountReadResult(AccountReadOutcome.UNSUPPORTED_REQUEST))
                } else if (accountCheckOnly && request.target == ReadOnlyPolicy.USER_PROFILE &&
                    accountProbeClaimed.compareAndSet(false, true) && !closed) {
                    onAccountRead(AccountReadResult(AccountReadOutcome.HTTP_ERROR))
                }
    }


    private fun probeProfile(body:ByteArray,original:LocalRequest):ByteArray {
        val reply=probes.execute(original.copy(body=body))
        try {if(reply.status!=200)throw AccountReadProbe.HttpFailure();return CaptureHttpTransport.decoded(reply)}
        finally {reply.body.fill(0)}
    }
    fun finishReceived():Boolean {
        accepting=false;server.close();channels.close();upstream.close()
        return responses.drain(45)
    }
    override fun close() {
        closed=true;accepting=false;responses.cancel()
        server.close();channels.close();upstream.close();probes.close();workers.shutdownNow()
        acceptor.shutdownGracefully(0,1,TimeUnit.SECONDS);io.shutdownGracefully(0,1,TimeUnit.SECONDS)
    }
}
