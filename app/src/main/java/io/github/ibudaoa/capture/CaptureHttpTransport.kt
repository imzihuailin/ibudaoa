package io.github.ibudaoa.capture

import java.io.Closeable
import java.io.InputStream
import java.net.Proxy
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import javax.net.ssl.HttpsURLConnection

internal enum class CaptureStage { CONNECT, TLS, HEADERS, BODY, UPSTREAM_REQUEST, UPSTREAM_HEADERS, UPSTREAM, RESPONSE, ACCOUNT, SCHOOL, SAVE, RETURN }
internal enum class CaptureFailure { NONE, REQUEST_LINE, HEADERS, AMBIGUOUS_LENGTH, BODY_LIMIT, BODY_ENCODING,
    TRUNCATED, IDLE, TLS, NETWORK, TIMEOUT, RESPONSE_ENCODING, RESPONSE_LIMIT, CANCELLED, CAPACITY, BLOCKED, BLOCKED_HOST, BLOCKED_METHOD, BLOCKED_PATH, BLOCKED_CONNECT_BODY }
internal data class CaptureTrace(val stage:CaptureStage, val failure:CaptureFailure=CaptureFailure.NONE, val millis:Long=0)
internal data class CaptureHttpReply(val status:Int,val headers:List<Pair<String,String>>,val body:ByteArray) {
    val encoding get()=headers.firstOrNull { it.first.equals("Content-Encoding",true) }?.second
}

/** System TLS verification and keep-alive pool; cancellation affects only this owner's connections. */
internal class CaptureHttpTransport(
    private val trace:(CaptureTrace)->Unit={},
    private val open:(String)->HttpsURLConnection={ URL("https://${ReadOnlyPolicy.HOST}$it").openConnection(Proxy.NO_PROXY) as HttpsURLConnection },
):Closeable {
    private val pending=ConcurrentHashMap.newKeySet<HttpsURLConnection>()
    @Volatile private var closed=false
    fun execute(request:LocalRequest, maintenance:Boolean=false):CaptureHttpReply {
        require(request.method=="POST" && (ReadOnlyPolicy.allows(request.method,request.target) ||
            maintenance && request.target==AccountProtocol.REFRESH))
        check(!closed)
        val connection=open(request.target)
        pending.add(connection)
        var consumed=false
        val started=System.nanoTime()
        try {
            check(!closed)
            connection.instanceFollowRedirects=false;connection.useCaches=false
            connection.connectTimeout=10_000;connection.readTimeout=10_000
            connection.requestMethod=request.method
            request.headers.filter { it.first.lowercase() !in hopHeaders(request.headers) }.forEach { (key,value) -> connection.addRequestProperty(key,value) }
            connection.setRequestProperty("Accept-Encoding","identity")
            connection.doOutput=true;connection.setFixedLengthStreamingMode(request.body.size)
            connection.outputStream.use { it.write(request.body) }
            trace(CaptureTrace(CaptureStage.UPSTREAM_REQUEST,millis=(System.nanoTime()-started)/1_000_000))
            val status=connection.responseCode
            require(status in 200..599)
            trace(CaptureTrace(CaptureStage.UPSTREAM_HEADERS,millis=(System.nanoTime()-started)/1_000_000))
            val headers=connection.headerFields.filterKeys { it!=null }.flatMap { (k,v) -> v.map { k!! to it } }
            val stream=if(status<400)connection.inputStream else connection.errorStream
            val body=stream?.use { bounded(it) } ?: byteArrayOf()
            if(closed){body.fill(0);throw java.io.IOException("Capture owner closed")}
            consumed=true
            trace(CaptureTrace(CaptureStage.UPSTREAM,millis=(System.nanoTime()-started)/1_000_000))
            return CaptureHttpReply(status,headers,body)
        } catch(e:Exception) {
            trace(CaptureTrace(if(e is CaptureProtocolException)CaptureStage.RESPONSE else CaptureStage.UPSTREAM,
                if(closed)CaptureFailure.CANCELLED else if(e is CaptureProtocolException)e.reason else
                    if(e is java.net.SocketTimeoutException)CaptureFailure.TIMEOUT else CaptureFailure.NETWORK,
                (System.nanoTime()-started)/1_000_000))
            throw e
        } finally {
            pending.remove(connection)
            // A completely consumed response is returned to Android's shared keep-alive pool.
            if(!consumed || closed) connection.disconnect()
        }
    }
    override fun close() {
        closed=true
        val owned=pending.toList()
        if(owned.isNotEmpty()) cancellation.execute {owned.forEach {runCatching {it.disconnect()}}}
    }
    companion object {
        private val cancellation=java.util.concurrent.Executors.newCachedThreadPool { runnable ->
            Thread(runnable,"capture-http-cancel").apply {isDaemon=true}
        }
        fun hopHeaders(headers:List<Pair<String,String>>):Set<String> = setOf("host","connection","proxy-connection","content-length",
            "transfer-encoding","accept-encoding","keep-alive","te","trailer","upgrade","proxy-authorization","expect") +
            headers.filter { it.first.equals("Connection",true) }.flatMap { it.second.split(',').map(String::trim).map(String::lowercase) }
        fun bounded(input:InputStream, limit:Int=HttpRequestReader.MAX_BODY):ByteArray {
            val out=SecretByteOutput();val buffer=ByteArray(8192)
            try {
                while(true) {
                    val n=input.read(buffer)
                    if(n<0)return out.toByteArray()
                    if(out.size()+n>limit)throw CaptureProtocolException(CaptureFailure.RESPONSE_LIMIT)
                    out.write(buffer,0,n)
                }
            } finally { buffer.fill(0);out.wipe() }
        }
        fun decoded(reply:CaptureHttpReply):ByteArray = when(reply.encoding?.lowercase()) {
            null,"identity" -> reply.body.clone()
            "gzip" -> GZIPInputStream(reply.body.inputStream()).use { bounded(it) }
            else -> throw CaptureProtocolException(CaptureFailure.RESPONSE_ENCODING)
        }
    }
}
