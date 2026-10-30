package io.github.ibudaoa.running

import io.github.ibudaoa.capture.BeforeRunDecoder
import java.io.Closeable
import java.net.Proxy
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.HttpsURLConnection
import kotlinx.serialization.json.*

/** Short-lived server credential. Never stored, logged, exported or replaced with provider defaults. */
internal class RunStorageGrant private constructor(val bucket:String,val endpointHost:String,val expiry:Instant,
    private val accessKey:String,private val secret:String,private val token:String) {
    fun headers(objectName:String,body:ByteArray,now:Instant):Map<String,String> {
        require(now.isBefore(expiry.minusSeconds(30)))
        require(objectName.matches(OBJECT_NAME) && body.size in 1..8*1024*1024)
        val date=DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'",Locale.US).withZone(ZoneOffset.UTC).format(now)
        val md5=Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(body))
        val canonical="PUT\n$md5\napplication/octet-stream\n$date\nx-oss-security-token:$token\n/$bucket/$objectName"
        val mac=Mac.getInstance("HmacSHA1");mac.init(SecretKeySpec(secret.toByteArray(),"HmacSHA1"))
        val signature=Base64.getEncoder().encodeToString(mac.doFinal(canonical.toByteArray()))
        return mapOf("Date" to date,"Content-Type" to "application/octet-stream","Content-MD5" to md5,
            "x-oss-security-token" to token,"Authorization" to "OSS $accessKey:$signature")
    }
    fun url(name:String):URL { require(name.matches(OBJECT_NAME));return URL("https://$bucket.$endpointHost/$name") }
    override fun toString()="RunStorageGrant(private)"
    companion object {
        internal val OBJECT_NAME=Regex("Public/Upload/file/run_(?:record|gyroscope)/[0-9]{4}-[0-9]{2}-[0-9]{2}/[0-9]{1,3}/[a-f0-9-]{36}\\.txt")
        fun decode(bytes:ByteArray,now:Instant):RunStorageGrant {
            val data=BeforeRunDecoder.decodeSuccessfulData(bytes)
            fun string(name:String,max:Int)=data[name]?.jsonPrimitive?.takeIf { it.isString }?.content?.also {
                require(it.length in 1..max && it.all { c -> c in '!'..'~' })
            } ?: error("Storage grant incomplete")
            // Fixed public service routing from pinned reference fbee94ec; credentials must still be fresh.
            val bucket=if("bucket" in data)string("bucket",63) else "lptiyu-data"
            require(bucket.matches(Regex("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]")))
            val endpoint=if("endpoint" in data)string("endpoint",160) else "https://oss-cn-hangzhou.aliyuncs.com"
            val uri=URI(if("://" in endpoint)endpoint else "https://$endpoint")
            require(uri.scheme in setOf("http","https") && uri.userInfo==null && uri.port==-1 && uri.rawQuery==null &&
                uri.rawFragment==null && uri.path in setOf("","/") && uri.host?.matches(Regex("oss-cn-[a-z0-9-]+\\.aliyuncs\\.com"))==true)
            val expiry=Instant.parse(string("Expiration",64))
            require(expiry.isAfter(now.plusSeconds(30)) && !expiry.isAfter(now.plusSeconds(86_400)))
            return RunStorageGrant(bucket,uri.host,expiry,string("AccessKeyId",256),string("AccessKeySecret",256),string("SecurityToken",8192))
        }
    }
}
internal class UploadedRunTrace private constructor(val objectName:String,val archiveSha256:String,
    internal val context:RunRequestContext,val distanceMeters:Double,val elapsedSeconds:Long,val allInsideArea:Boolean,val samples:Int,val insideSamples:Int,
    private val source:RunTraceArchive) {
    fun contains(observation:ObservedRunCheckpoint)=source.contains(observation)
    override fun toString()="UploadedRunTrace(private)"
    companion object {
        fun confirmed(name:String,archive:RunTraceArchive,etag:String,body:ByteArray):UploadedRunTrace {
            val digest=MessageDigest.getInstance("MD5").digest(body).joinToString("") { "%02x".format(it.toInt() and 255) }
            require(etag.trim().removeSurrounding("\"").equals(digest,true)) { "Uploaded trace acknowledgement mismatch" }
            val sha=MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it.toInt() and 255) }
            require(sha==archive.sha256 && name.matches(RunStorageGrant.OBJECT_NAME))
            return UploadedRunTrace(name,sha,archive.context,archive.distanceMeters,archive.elapsedSeconds,archive.allInsideArea,archive.pointCount,archive.insidePointCount,archive)
        }
    }
}
internal class RunObjectUpload(private val factory:(URL)->HttpsURLConnection={
    it.openConnection(Proxy.NO_PROXY) as HttpsURLConnection
}):Closeable {
    private var active:HttpsURLConnection?=null
    private var closed=false
    fun upload(grant:RunStorageGrant,archive:RunTraceArchive,now:Instant):UploadedRunTrace {
        val name=objectName("run_record",now)
        val body=archive.uploadBytes()
        return try { UploadedRunTrace.confirmed(name,archive,putFile(grant,name,body,now),body) } finally { body.fill(0) }
    }
    fun objectName(kind:String,now:Instant):String {
        require(kind in setOf("run_record","run_gyroscope"))
        val day=now.atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate().toString()
        return "Public/Upload/file/$kind/$day/1/${UUID.randomUUID()}.txt"
    }
    fun putFile(grant:RunStorageGrant,name:String,body:ByteArray,now:Instant):String {
        val headers=grant.headers(name,body,now)
        val connection=synchronized(this) {
            check(!closed && active==null);factory(grant.url(name)).also { active=it }
        }
        try {
            connection.instanceFollowRedirects=false;connection.useCaches=false
            connection.connectTimeout=15_000;connection.readTimeout=20_000;connection.requestMethod="PUT";connection.doOutput=true
            headers.forEach { connection.setRequestProperty(it.key,it.value) }
            connection.setFixedLengthStreamingMode(body.size)
            synchronized(this) { check(!closed) }
            connection.outputStream.use { it.write(body) }
            check(connection.responseCode==200) { "Trace upload not acknowledged" }
            val etag=connection.getHeaderField("ETag") ?: error("Trace acknowledgement missing")
            val expected=MessageDigest.getInstance("MD5").digest(body).joinToString("") { "%02x".format(it.toInt() and 255) }
            check(etag.trim().removeSurrounding("\"").equals(expected,true)) { "Uploaded file checksum mismatch" }
            return etag
        } finally { connection.disconnect();synchronized(this) { if(active===connection)active=null } }
    }
    @Synchronized override fun close() { closed=true;active?.disconnect();active=null }
}
