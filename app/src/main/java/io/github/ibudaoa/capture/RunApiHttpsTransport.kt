package io.github.ibudaoa.capture

import java.net.Proxy
import java.net.URL
import java.util.zip.GZIPInputStream
import javax.net.ssl.HttpsURLConnection

/** One fixed-host POST, system TLS trust, no redirects or application-level replay. */
internal class RunApiHttpsTransport(private val factory:(URL)->HttpsURLConnection={
    it.openConnection(Proxy.NO_PROXY) as HttpsURLConnection
}):AccountReadConnection {
    private var active:HttpsURLConnection?=null
    private var closed=false
    override fun send(request:LocalRequest):ByteArray {
        require(request.method=="POST" && RunEndpoint.entries.any { it.path==request.target } && request.body.size in 1..131_072)
        require(request.headers.count { it.first.equals("Content-Type",true) }==1)
        require(request.headers.all { (k,v) -> k.lowercase() in setOf("content-type","user-agent") &&
            k.none { it.isISOControl() } && v.none { it.isISOControl() } })
        val connection=synchronized(this) {
            check(!closed && active==null)
            factory(URL("https://${ReadOnlyPolicy.HOST}${request.target}")).also { active=it }
        }
        try {
            connection.instanceFollowRedirects=false;connection.useCaches=false
            connection.connectTimeout=10_000;connection.readTimeout=15_000;connection.requestMethod="POST"
            request.headers.forEach { connection.addRequestProperty(it.first,it.second) }
            connection.setRequestProperty("Accept-Encoding","identity");connection.setRequestProperty("Connection","close")
            connection.doOutput=true;connection.setFixedLengthStreamingMode(request.body.size)
            synchronized(this) { check(!closed) }
            connection.outputStream.use { it.write(request.body) }
            check(connection.responseCode==200) { "Run request not acknowledged" }
            val stream=when(connection.contentEncoding?.lowercase()) {
                null,"identity" -> connection.inputStream
                "gzip" -> GZIPInputStream(connection.inputStream)
                else -> error("Unsupported run response")
            }
            return stream.use { input ->
                val out=SecretByteOutput();val buffer=ByteArray(8192)
                try {
                    while(true) {
                        val n=input.read(buffer);if(n<0)break
                        require(out.size()+n<=io.github.ibudaoa.diagnostics.DiagnosticCodec.MAX_BYTES)
                        out.write(buffer,0,n)
                    }
                    out.toByteArray()
                } finally { buffer.fill(0);out.wipe() }
            }
        } finally { connection.disconnect();synchronized(this) { if(active===connection)active=null } }
    }
    @Synchronized override fun close() { closed=true;active?.disconnect();active=null }
}
