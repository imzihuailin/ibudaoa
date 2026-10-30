package io.github.ibudaoa.capture

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Proxy
import java.net.URL
import java.util.zip.GZIPInputStream
import javax.net.ssl.HttpsURLConnection

/** Independent system-trusted HTTPS client. Only User/User; close interrupts pending IO. */
internal class AccountReadTransport(private val maintenance:Boolean=false):AccountReadConnection {
    private var connection:HttpsURLConnection?=null
    private var closed=false
    override fun send(request:LocalRequest):ByteArray {
        require(request.method=="POST" && (request.target==ReadOnlyPolicy.USER_PROFILE || maintenance && request.target in setOf(ReadOnlyPolicy.BEFORE_RUN,AccountProtocol.REFRESH)))
        val current=synchronized(this) {
            check(!closed && connection==null)
            (URL("https://${ReadOnlyPolicy.HOST}${request.target}").openConnection(Proxy.NO_PROXY) as HttpsURLConnection)
                .also { connection=it }
        }
        try {
            current.instanceFollowRedirects=false;current.useCaches=false
            current.connectTimeout=10_000;current.readTimeout=10_000;current.requestMethod="POST"
            request.headers.filter { it.first.lowercase() !in HOP }.forEach { current.addRequestProperty(it.first,it.second) }
            current.setRequestProperty("Accept-Encoding","identity");current.setRequestProperty("Connection","close")
            current.doOutput=true;current.setFixedLengthStreamingMode(request.body.size)
            synchronized(this) { check(!closed) }
            current.outputStream.use { it.write(request.body) }
            if(current.responseCode!=200) throw AccountReadProbe.HttpFailure()
            return when(current.contentEncoding?.lowercase()) {
                null,"identity" -> current.inputStream.use(::bounded)
                "gzip" -> GZIPInputStream(current.inputStream).use(::bounded)
                else -> throw AccountReadProbe.HttpFailure()
            }
        } finally { current.disconnect();synchronized(this) { if(connection===current) connection=null } }
    }
    @Synchronized override fun close() { closed=true;connection?.disconnect();connection=null }
    private fun bounded(input:InputStream):ByteArray {
        val out=SecretByteOutput()
        val buffer=ByteArray(8192)
        try {
            while(true) {
                val count=input.read(buffer)
                if(count<0) return out.toByteArray()
                require(out.size()+count<=HttpRequestReader.MAX_BODY)
                out.write(buffer,0,count)
            }
        } finally { buffer.fill(0);out.wipe() }
    }
    companion object {
        private val HOP=setOf("host","connection","proxy-connection","content-length","transfer-encoding","accept-encoding",
            "keep-alive","te","trailer","upgrade","proxy-authorization")
    }
}
internal class SecretByteOutput:ByteArrayOutputStream() { fun wipe() { buf.fill(0);reset() } }
