package io.github.ibudaoa.capture

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLException

/** Loopback-only, one request per TLS connection, with exact endpoint filtering before upstream IO. */
class ReadOnlyProxy(
    private val tls: SSLContext,
    private val onBeforeRun: (ByteArray) -> Unit,
    private val onBlocked: (BlockedRequestKind) -> Unit,
    private val onProblem: (CaptureProblem) -> Unit,
    private val onAccountProfile: (ByteArray) -> Unit = {},
    private val accountCheckOnly: Boolean = false,
    private val onAccountRead: (AccountReadResult) -> Unit = {},
    private val onAcceptedAccount: ((LocalRequest)->Unit)? = null,
    private val accountMaintenance:Boolean=false,
    private val onAccountProtocol:((LocalRequest,ByteArray)->Unit)?=null,
) : Closeable {
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val upstream = ConcurrentHashMap.newKeySet<HttpsURLConnection>()
    private val workers = ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, ArrayBlockingQueue(8))
    @Volatile private var closed = false
    private val accountProbeClaimed=AtomicBoolean(false)
    private val responses = CaptureResponseQueue()
    private val probes = ConcurrentHashMap.newKeySet<HttpsURLConnection>()
    @Volatile private var accepting = true
    private val hopHeaders=setOf("host","connection","proxy-connection","content-length","transfer-encoding",
        "accept-encoding","keep-alive","te","trailer","upgrade","proxy-authorization")

    fun serve() {
        while (accepting && !closed) {
            val socket = try { server.accept() } catch (_: Exception) { break }
            sockets.add(socket)
            try { workers.execute { handle(socket) } }
            catch (_: Exception) { sockets.remove(socket); runCatching { socket.close() } }
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = 15_000
            val connect = HttpRequestReader.read(socket.getInputStream()) ?: return
            if (connect.method != "CONNECT" || !ReadOnlyPolicy.allowsConnect(connect.target)) {
                onBlocked(BlockedRequestKind.OTHER); deny(socket.getOutputStream()); return
            }
            socket.getOutputStream().apply {
                write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray()); flush()
            }
            (tls.socketFactory.createSocket(socket, ReadOnlyPolicy.HOST, 443, false) as SSLSocket).use { secure ->
                secure.useClientMode = false
                secure.enabledProtocols = secure.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
                secure.soTimeout = 15_000
                secure.startHandshake()
                val request = HttpRequestReader.read(secure.inputStream) ?: return
                try {
                    val hosts = request.headers.filter { it.first.equals("Host", true) }
                    val maintenancePath=accountMaintenance && request.method=="POST" && request.target in setOf(ReadOnlyPolicy.BEFORE_RUN,AccountProtocol.REFRESH)
                    if ((!ReadOnlyPolicy.allows(request.method, request.target) && !maintenancePath) ||
                        (accountCheckOnly && request.target!=ReadOnlyPolicy.USER_PROFILE && !maintenancePath) || hosts.size != 1 ||
                        hosts.single().second !in setOf(ReadOnlyPolicy.HOST, "${ReadOnlyPolicy.HOST}:443")) {
                        onBlocked(ReadOnlyPolicy.blockedKind(request.target));request.body.fill(0); deny(secure.outputStream); return
                    }
                    forward(request, secure.outputStream)
                } finally { request.body.fill(0) }
            }
        } catch (_: SSLException) {
            if (!closed && accepting) onProblem(CaptureProblem.TLS)
        } catch (_: CaptureProtocolException) {
            if (!closed && accepting) onProblem(CaptureProblem.REQUEST_FORMAT)
        } catch (_: Exception) {
            if (!closed && accepting) onProblem(CaptureProblem.NETWORK)
        } finally {
            sockets.remove(socket)
            runCatching { socket.close() }
        }
    }

    private fun forward(request: LocalRequest, output: OutputStream) {
        // No custom trust manager, hostname verifier, credentials, redirects or disk cache.
        val connection = URL("https://${ReadOnlyPolicy.HOST}${request.target}")
            .openConnection(Proxy.NO_PROXY) as HttpsURLConnection
        upstream.add(connection)
        try {
            if (closed) return
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.requestMethod = request.method
            val hop = hopHeaders
            request.headers.filter { it.first.lowercase() !in hop }.forEach { (key, value) ->
                connection.addRequestProperty(key, value)
            }
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("Connection", "close")
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(request.body.size)
            connection.outputStream.use { it.write(request.body) }
            val status = connection.responseCode
            if (status !in 200..599) throw CaptureProtocolException()
            val stream = if (status < 400) connection.inputStream else connection.errorStream
            val raw = stream?.use(::bounded) ?: ByteArray(0)
            try {
                if (!closed && accepting && request.target in setOf(ReadOnlyPolicy.BEFORE_RUN, ReadOnlyPolicy.USER_PROFILE, AccountProtocol.REFRESH)) {
                    val ownedRequest = request.copy(body = request.body.clone())
                    val ownedResponse = raw.clone()
                    val encoding = connection.contentEncoding
                    responses.submit(action = {
                        if (!closed) try { processResponse(ownedRequest, ownedResponse, status, encoding) }
                        catch (_: Exception) { if (!closed) onProblem(CaptureProblem.INVALID_RESPONSE) }
                    }, wipe = { ownedRequest.body.fill(0); ownedResponse.fill(0) })
                }
                output.write("HTTP/1.1 $status Upstream\r\n".toByteArray())
                connection.headerFields.forEach { (key, values) ->
                    if (key != null && key.lowercase() !in hop) values.forEach { value ->
                        if (!value.contains('\r') && !value.contains('\n')) output.write("$key: $value\r\n".toByteArray())
                    }
                }
                output.write("Content-Length: ${raw.size}\r\nConnection: close\r\n\r\n".toByteArray())
                output.write(raw); output.flush()
            } finally { raw.fill(0) }
        } finally { request.body.fill(0);upstream.remove(connection); connection.disconnect() }
    }

    private fun processResponse(request: LocalRequest, raw: ByteArray, status: Int, encoding: String?) {
                if (request.target in setOf(ReadOnlyPolicy.BEFORE_RUN, ReadOnlyPolicy.USER_PROFILE,AccountProtocol.REFRESH) && status == 200 && !closed) {
                    val bytes = when (encoding?.lowercase()) {
                        null, "identity" -> raw
                        "gzip" -> if (request.target == ReadOnlyPolicy.USER_PROFILE)
                            runCatching { GZIPInputStream(raw.inputStream()).use(::bounded) }.getOrNull()
                        else GZIPInputStream(raw.inputStream()).use(::bounded)
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

    /** A separate client request to one exact read endpoint; no session, retry, redirect or refresh. */
    private fun probeProfile(body:ByteArray, original:LocalRequest):ByteArray {
        if (closed) throw AccountReadProbe.HttpFailure()
        val connection=URL("https://${ReadOnlyPolicy.HOST}${ReadOnlyPolicy.USER_PROFILE}")
            .openConnection(Proxy.NO_PROXY) as HttpsURLConnection
        probes.add(connection)
        try {
            if (closed) throw AccountReadProbe.HttpFailure()
            connection.instanceFollowRedirects=false;connection.useCaches=false
            connection.connectTimeout=10_000;connection.readTimeout=10_000;connection.requestMethod="POST"
            original.headers.filter { it.first.lowercase() !in hopHeaders }.forEach { (key,value) ->
                connection.addRequestProperty(key,value)
            }
            connection.setRequestProperty("Accept-Encoding","identity")
            connection.setRequestProperty("Connection","close")
            connection.doOutput=true;connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            if (connection.responseCode!=200) throw AccountReadProbe.HttpFailure()
            return when(connection.contentEncoding?.lowercase()) {
                null,"identity" -> connection.inputStream.use(::bounded)
                "gzip" -> GZIPInputStream(connection.inputStream).use(::bounded)
                else -> throw AccountReadProbe.HttpFailure()
            }
        } finally { probes.remove(connection);connection.disconnect() }
    }

    private fun bounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return output.toByteArray()
            if (output.size() + count > HttpRequestReader.MAX_BODY) throw CaptureProtocolException()
            output.write(buffer, 0, count)
        }
    }

    private fun deny(output: OutputStream) {
        val bytes = "{\"status\":0,\"info\":\"只读诊断已阻止该请求\"}".toByteArray()
        output.write(("HTTP/1.1 403 Forbidden\r\nContent-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
        output.write(bytes); output.flush()
    }

    /** Stop network interception immediately; process only already received response copies. */
    fun finishReceived(): Boolean {
        accepting = false
        runCatching { server.close() }
        sockets.forEach { runCatching { it.close() } }
        upstream.forEach { runCatching { it.disconnect() } }
        return responses.drain(45)
    }

    override fun close() {
        closed = true
        accepting = false
        responses.cancel()
        runCatching { server.close() }
        sockets.forEach { runCatching { it.close() } }
        upstream.forEach { runCatching { it.disconnect() } }
        probes.forEach { runCatching { it.disconnect() } }
        workers.shutdownNow()
    }
}
