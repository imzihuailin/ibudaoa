package io.github.ibudaoa.running

import io.github.ibudaoa.maps.DisplayFix
import io.github.ibudaoa.domain.CampusCoordinate
import io.github.ibudaoa.diagnostics.DiagnosticCodec
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.IvParameterSpec
import org.junit.Assert.*
import org.junit.Test

internal object UploadFixture {
    val now=Instant.parse("2026-10-29T02:00:00Z")
    val context=RunRequestContext(42,1,2,3,listOf("21","22"),1_700_000_000,"a".repeat(32))
    private val base=DiagnosticCodec.decode(RunningPlanTest().config.let { DiagnosticCodec.encode(it) }
        .replace("p0","21").replace("p1","22").replace("p2","23"))
    val config=base.copy(config=JsonObject(base.config+mapOf("gyroscope_type" to JsonPrimitive(0))))
    fun fix(lat:Double,t:Long,speed:Float?=3f)=DisplayFix(CampusCoordinate(lat,114.0),3f,t,1_700_000_000_000+t,speed)
    val trace=listOf(MeasuredRunFix(fix(30.0,1000),0,0.0,true),MeasuredRunFix(fix(30.001,11000),10,100.0,false),
        MeasuredRunFix(fix(30.002,21000),20,200.0,false))
    val progress=RunProgressSummary(ActualDistanceSummary(DistancePhase.PAUSED,200.0),2,2,25)
    val points=listOf(ObservedRunCheckpoint("21",trace[1].fix,100.0),ObservedRunCheckpoint("22",trace[2].fix,200.0))
    fun archive()=RunTraceArchive.build(context,config,progress,trace)
    fun plain(archive:RunTraceArchive):JsonArray {
        val cipher=Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE,SecretKeySpec("Wet2C8d34f62ndi3".toByteArray(),"AES"),IvParameterSpec("K6iv85jBD8jgf32D".toByteArray()))
        return Json.parseToJsonElement(cipher.doFinal(Base64.getMimeDecoder().decode(archive.uploadBytes())).toString(Charsets.UTF_8)).jsonArray
    }
    fun etag(body:ByteArray)=MessageDigest.getInstance("MD5").digest(body).joinToString("") { "%02x".format(it.toInt() and 255) }
    const val key="Public/Upload/file/run_record/2026-10-29/1/00000000-0000-0000-0000-000000000001.txt"
    fun receipt(archive:RunTraceArchive)=archive.uploadBytes().let { UploadedRunTrace.confirmed(key,archive,"\"${etag(it)}\"",it) }
    fun grant(endpoint:String="https://oss-cn-hangzhou.aliyuncs.com",expiry:String="2026-10-29T03:00:00Z",bucket:String="synthetic-run-data")=
        """{"status":1,"data":{"bucket":"$bucket","endpoint":"$endpoint","Expiration":"$expiry","AccessKeyId":"FAKE_ACCESS_KEY","AccessKeySecret":"FAKE_STORAGE_SECRET","SecurityToken":"FAKE_SECURITY_TOKEN"}}""".toByteArray()
}
class RunUploadTest {
    @Test fun `credential only grant uses pinned public service routing without inventing credentials`() {
        val original=Json.parseToJsonElement(UploadFixture.grant().toString(Charsets.UTF_8)).jsonObject
        val data=original.getValue("data").jsonObject
        val bytes=JsonObject(original+mapOf("data" to JsonObject(data-filterKeys(data)))).toString().toByteArray()
        val grant=RunStorageGrant.decode(bytes,UploadFixture.now)
        assertEquals("https://lptiyu-data.oss-cn-hangzhou.aliyuncs.com/"+UploadFixture.key,grant.url(UploadFixture.key).toString())
        for(key in listOf("AccessKeyId","AccessKeySecret","SecurityToken","Expiration")) {
            val missing=JsonObject(original+mapOf("data" to JsonObject(data-filterKeys(data)-key))).toString().toByteArray()
            assertTrue(key,runCatching { RunStorageGrant.decode(missing,UploadFixture.now) }.isFailure)
        }
        val bad=JsonObject(original+mapOf("data" to JsonObject(data+mapOf("endpoint" to JsonNull)))).toString().toByteArray()
        assertTrue(runCatching { RunStorageGrant.decode(bad,UploadFixture.now) }.isFailure)
    }
    private fun filterKeys(data:JsonObject)=data.keys.filter { it in setOf("bucket","endpoint") }.toSet()
    @Test fun `archive decrypts into only measured coordinates and actual segment distance and time`() {
        UploadFixture.archive().use { archive ->
            val rows=UploadFixture.plain(archive)
            assertEquals(3,rows.size)
            rows.zip(UploadFixture.trace).forEach { (row,sample) ->
                assertEquals(sample.fix.coordinate.latitude.toFloat().toDouble(),row.jsonObject.getValue("a").jsonPrimitive.double,0.0)
                assertEquals(sample.fix.coordinate.longitude.toFloat().toDouble(),row.jsonObject.getValue("o").jsonPrimitive.double,0.0)
            }
            assertEquals(0.2,rows.sumOf { it.jsonObject["b"]?.jsonPrimitive?.double ?: 0.0 },0.000001)
            assertEquals(25L,rows.sumOf { it.jsonObject["c"]?.jsonPrimitive?.long ?: 0 })
            assertEquals(1,rows.last().jsonObject.getValue("p").jsonPrimitive.int)
            assertFalse(archive.toString().contains("CampusCoordinate"))
        }
    }
    @Test fun `pause and missing GPS boundaries preserve zero bridge distance without invented samples`() {
        val original=UploadFixture.trace
        val trace=listOf(original[0],original[1],MeasuredRunFix(UploadFixture.fix(30.005,21000),20,100.0,true),
            MeasuredRunFix(UploadFixture.fix(30.006,31000),30,200.0,false))
        RunTraceArchive.build(UploadFixture.context,UploadFixture.config,UploadFixture.progress.copy(elapsedSeconds=35),trace).use {
            val rows=UploadFixture.plain(it)
            assertEquals(4,rows.size);assertEquals(1,rows[1].jsonObject.getValue("p").jsonPrimitive.int)
            assertEquals(0.2,rows.sumOf { r -> r.jsonObject["b"]?.jsonPrimitive?.double ?: 0.0 },0.000001)
        }
    }
    @Test fun `unmeasured speed is omitted and outside area is reported without removing actual distance`() {
        val trace=UploadFixture.trace.toMutableList();trace[2]=MeasuredRunFix(UploadFixture.fix(30.02,21000,null),20,200.0,false)
        RunTraceArchive.build(UploadFixture.context,UploadFixture.config,UploadFixture.progress,trace).use {
            assertFalse(it.allInsideArea);assertEquals(2,it.insidePointCount)
            assertFalse(UploadFixture.plain(it).last().jsonObject.containsKey("s"))
            assertEquals(1,UploadFixture.plain(it).last().jsonObject.getValue("l").jsonPrimitive.int)
            assertEquals(200.0,it.distanceMeters,0.0)
        }
    }
    @Test fun `changed scope mixed points inconsistent total and reordered GPS cannot be archived`() {
        assertTrue(runCatching { RunTraceArchive.build(RunRequestContext(42,1,2,4,listOf("21","22"),1700000000,"a".repeat(32)),UploadFixture.config,UploadFixture.progress,UploadFixture.trace) }.isFailure)
        assertTrue(runCatching { RunTraceArchive.build(RunRequestContext(42,1,2,3,listOf("99","22"),1700000000,"a".repeat(32)),UploadFixture.config,UploadFixture.progress,UploadFixture.trace) }.isFailure)
        assertTrue(runCatching { RunTraceArchive.build(UploadFixture.context,UploadFixture.config,UploadFixture.progress.copy(distance=ActualDistanceSummary(meters=201.0)),UploadFixture.trace) }.isFailure)
        assertTrue(runCatching { RunTraceArchive.build(UploadFixture.context,UploadFixture.config,UploadFixture.progress,UploadFixture.trace.reversed()) }.isFailure)
    }
    @Test fun `closing archive wipes owned payload and prevents further upload copies`() {
        val archive=UploadFixture.archive();val copy=archive.uploadBytes();copy[0]=0
        assertTrue(archive.uploadBytes()[0]!=0.toByte());archive.close()
        assertTrue(runCatching { archive.uploadBytes() }.isFailure)
    }
    @Test fun `uploaded receipt requires both exact payload and provider ETag`() {
        UploadFixture.archive().use {
            assertTrue(runCatching { UploadedRunTrace.confirmed(UploadFixture.key,it,"0".repeat(32),it.uploadBytes()) }.isFailure)
            val other="wrong uploaded body".toByteArray()
            assertTrue(runCatching { UploadedRunTrace.confirmed(UploadFixture.key,it,UploadFixture.etag(other),other) }.isFailure)
            assertEquals(it.sha256,UploadFixture.receipt(it).archiveSha256)
        }
    }
    @Test fun `storage grant rejects arbitrary hosts credentials in URL traversal and expiry`() {
        for(host in listOf("https://127.0.0.1","https://evil.test","https://oss-cn-hangzhou.aliyuncs.com.evil.test",
            "https://user@oss-cn-hangzhou.aliyuncs.com","https://oss-cn-hangzhou.aliyuncs.com:443","https://oss-cn-hangzhou.aliyuncs.com/private"))
            assertTrue(runCatching { RunStorageGrant.decode(UploadFixture.grant(endpoint=host),UploadFixture.now) }.isFailure)
        for(expiry in listOf("2026-10-29T01:00:00Z","2026-10-29T02:00:20Z","2026-12-29T02:00:00Z"))
            assertTrue(runCatching { RunStorageGrant.decode(UploadFixture.grant(expiry=expiry),UploadFixture.now) }.isFailure)
        assertTrue(runCatching { RunStorageGrant.decode(UploadFixture.grant(bucket="../other"),UploadFixture.now) }.isFailure)
    }
    @Test fun `provider HTTP endpoint is upgraded and signed headers bind exact body and name`() {
        val grant=RunStorageGrant.decode(UploadFixture.grant(endpoint="http://oss-cn-hangzhou.aliyuncs.com"),UploadFixture.now)
        assertEquals("https",grant.url(UploadFixture.key).protocol)
        val headers=grant.headers(UploadFixture.key,"test".toByteArray(),UploadFixture.now)
        assertEquals("CY9rzUYh03PK3k6DJie09g==",headers["Content-MD5"])
        assertTrue(headers.getValue("Authorization").startsWith("OSS FAKE_ACCESS_KEY:"))
        assertTrue(runCatching { grant.url("../private") }.isFailure)
        assertFalse(grant.toString().contains("FAKE"))
    }
    @Test fun `finish fields use uploaded ownership exact observed fixes and actual step intervals`() {
        UploadFixture.archive().use {
            val fields=RunFinishFields.build(UploadFixture.context,UploadFixture.receipt(it),UploadFixture.points,RunFinishMeasurements(60,listOf(60),JsonArray(emptyList())))
            assertEquals(UploadFixture.key,fields["record_file"]);assertEquals("0.20",fields["distance"])
            assertEquals("3",fields["pass_total"]);assertEquals("3",fields["pass_effective_num"])
            assertEquals("1700000025",fields["end_time"])
            assertEquals("111c40ba201981fd30dd55da40bb2109",fields["record_str"])
            val logs=Json.parseToJsonElement(fields.getValue("log_data")).jsonArray
            assertEquals(UploadFixture.points[0].fix.sdkTimeMillis!!/1000,logs[0].jsonObject.getValue("time").jsonPrimitive.long)
            assertEquals(UploadFixture.points[0].fix.coordinate.latitude.toFloat().toDouble(),logs[0].jsonObject.getValue("latitude").jsonPrimitive.double,0.0)
        }
    }
    @Test fun `unvisited fixed point remains unvisited and fabricated checkpoint observation is refused`() {
        UploadFixture.archive().use {
            val receipt=UploadFixture.receipt(it);val sensors=RunFinishMeasurements(60,listOf(60),JsonArray(emptyList()))
            val fields=RunFinishFields.build(UploadFixture.context,receipt,UploadFixture.points.take(1),sensors)
            val logs=Json.parseToJsonElement(fields.getValue("log_data")).jsonArray
            assertEquals(0L,logs[1].jsonObject.getValue("time").jsonPrimitive.long)
            assertEquals(0.0,logs[1].jsonObject.getValue("latitude").jsonPrimitive.double,0.0)
            val fabricated=ObservedRunCheckpoint("22",UploadFixture.fix(30.009,22000),200.0)
            assertTrue(runCatching { RunFinishFields.build(UploadFixture.context,receipt,listOf(fabricated),sensors) }.isFailure)
            assertTrue(runCatching { RunFinishMeasurements(60,listOf(61),JsonArray(emptyList())) }.isFailure)
            val changed=RunRequestContext(43,1,2,3,listOf("21","22"),1700000000,"a".repeat(32))
            assertTrue(runCatching { RunFinishFields.build(changed,receipt,emptyList(),sensors) }.isFailure)
        }
    }
}
