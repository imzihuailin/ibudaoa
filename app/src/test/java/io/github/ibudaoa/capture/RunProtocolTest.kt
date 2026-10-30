package io.github.ibudaoa.capture

import io.github.ibudaoa.running.*
import kotlinx.serialization.json.*
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.IvParameterSpec
import org.junit.Assert.*
import org.junit.Test

internal object RunProtocolFixture {
    const val now=1_790_000_000L
    fun material()="""{"schema":1,"strip_spaces":false,"parameters":{"uid":"42","token":"FAKE_TOKEN","school_id":"1","version":"231","version_name":"4.3.1","mobileModel":"Dummy Model","mobileDeviceId":"FAKE_DEVICE","mobileOsVersion":"16","ostype":"1"},"umid":"SYNTHETIC_UMID","refresh_token":"FAKE_REFRESH"}""".toByteArray()
    fun fields(request:LocalRequest):Map<String,String> {
        fun form(s:String)=s.split('&').associate { val pair=it.split('=',limit=2);URLDecoder.decode(pair[0],"UTF-8") to URLDecoder.decode(pair[1],"UTF-8") }
        val parsed=form(request.body.toString(Charsets.UTF_8))
        if("key" !in parsed)return parsed
        val cipher=Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE,SecretKeySpec("goMEzJ1pDpncNHb9".toByteArray(),"AES"),IvParameterSpec("ZUrAS2lUidjyY2gK".toByteArray()))
        val decoded=cipher.doFinal(Base64.getMimeDecoder().decode(parsed.getValue("key")))
        return Json.parseToJsonElement(decoded.toString(Charsets.UTF_8)).jsonObject.mapValues { it.value.jsonPrimitive.content }
    }
    class Store:AccountSessionStorage {
        override fun load()=material()
        override fun save(plain:ByteArray) {}
        override fun delete() {}
    }
    fun session()=ForegroundAccountSession().apply { attachStorage(Store());restore();onForeground() }
}
class RunProtocolTest {
    private val ctx=UploadFixture.context
    private fun request(endpoint:RunEndpoint,fields:Map<String,String>)=RunProtocol.request(RunProtocolFixture.material(),endpoint,ctx,fields,RunProtocolFixture.now,123456)
    private fun md5(text:String)=MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    @Test fun `legacy registration signs actual common parameters and exact fixed point ids`() {
        val r=request(RunEndpoint.REGISTER,mapOf("game_id" to "3","point_ids" to "21,22"))
        val p=RunProtocolFixture.fields(r)
        assertEquals("/v3/api.php/Run/getTimestampV278",r.target)
        assertEquals("21,22",p["point_ids"])
        val expected=p.toSortedMap().filterKeys { it!="sign" }.entries.joinToString("") { it.key+it.value }+"rDJiNB9j7vD2"
        assertEquals(md5(expected),p["sign"])
        assertTrue(p.containsKey("mobileDeviceId"));assertFalse(p.keys.any { it.startsWith("lp") })
        assertFalse(p.containsKey("refresh_token"));assertFalse(p.containsKey("student_num"))
    }
    @Test fun `location uses dynamic umid proof and signature B with device excluded from signature`() {
        val p=RunProtocolFixture.fields(request(RunEndpoint.LOCATION,mapOf("game_id" to "3","point_ids" to "21,22","latitude" to "30.001","longitude" to "114.0")))
        val proof=p.keys.single { it.startsWith("lp") }
        assertEquals("lp${"Dummy Model"[1].lowercaseChar()}${RunProtocolFixture.now.toString()[3]}",proof)
        val expected="eFoqdHOdDy6ViJ0i"+p.toSortedMap().filterKeys { it !in setOf("sign","mobileDeviceId") }.entries.joinToString("") { it.key+it.value }
        assertEquals(md5(expected),p["sign"])
        assertNotEquals("a".repeat(32),p[proof])
    }
    @Test fun `finish keeps exact registration proof but independently derives record signature`() {
        UploadFixture.archive().use { archive ->
            val fields=RunFinishFields.build(ctx,UploadFixture.receipt(archive),UploadFixture.points,RunFinishMeasurements(60,listOf(60),JsonArray(emptyList())))
            val r=request(RunEndpoint.FINISH,fields);val p=RunProtocolFixture.fields(r)
            assertTrue(r.body.toString(Charsets.UTF_8).startsWith("key="))
            assertEquals(ctx.registrationProof,p[p.keys.single { it.startsWith("lp") }])
            assertNotEquals(ctx.registrationProof,p["record_str"])
            assertEquals(UploadFixture.key,p["record_file"])
            assertEquals("FAKE_TOKEN",p["token"]);assertFalse(p.values.contains("FAKE_REFRESH"))
        }
    }
    @Test fun `foreign scope reordered points unknown keys and credential overrides cannot be signed`() {
        for(fields in listOf(mapOf("game_id" to "4","point_ids" to "21,22"),mapOf("game_id" to "3","point_ids" to "22,21"),
            mapOf("game_id" to "3","point_ids" to "21,22","token" to "OTHER"),mapOf("game_id" to "3","point_ids" to "21,22","unexpected" to "x")))
            assertTrue(runCatching { request(RunEndpoint.REGISTER,fields) }.isFailure)
        val changed=RunRequestContext(43,1,2,3,listOf("21","22"),1700000000,"a".repeat(32))
        assertTrue(runCatching { RunProtocol.request(RunProtocolFixture.material(),RunEndpoint.ZONE,changed,mapOf("run_zone_id" to "3"),RunProtocolFixture.now,123456) }.isFailure)
        assertTrue(runCatching { request(RunEndpoint.LOCATION,mapOf("game_id" to "3","point_ids" to "21,22","latitude" to "NaN","longitude" to "114")) }.isFailure)
    }
    @Test fun `record query uses a concrete id and never broadens passive capture whitelist`() {
        val p=RunProtocolFixture.fields(request(RunEndpoint.RECORD,mapOf("record_id" to "99","record_uid" to "42","year_num" to "2026")))
        assertEquals("99",p["record_id"])
        assertTrue(runCatching { request(RunEndpoint.RECORD,mapOf("record_id" to "latest")) }.isFailure)
        assertTrue(runCatching { request(RunEndpoint.RECORD,mapOf("record_id" to "99","record_uid" to "43")) }.isFailure)
        for(endpoint in RunEndpoint.entries) assertFalse(ReadOnlyPolicy.allows("POST",endpoint.path))
    }
    @Test fun `account session wipes sent body and closes connection on acknowledgement or preflight rejection`() {
        val session=RunProtocolFixture.session()
        var sent:LocalRequest?=null;var closed=false
        val c=object:AccountReadConnection {
            override fun send(request:LocalRequest):ByteArray { sent=request;assertEquals("42",RunProtocolFixture.fields(request)["uid"]);return "{}".toByteArray() }
            override fun close() { closed=true }
        }
        session.runRequest(c,RunEndpoint.ZONE,ctx,mapOf("run_zone_id" to "3"),RunProtocolFixture.now,123456)
        assertTrue(closed);assertTrue(sent!!.body.all { it==0.toByte() })
        closed=false;session.onBackground()
        assertTrue(runCatching { session.runRequest(c,RunEndpoint.ZONE,ctx,mapOf("run_zone_id" to "3"),RunProtocolFixture.now,123456) }.isFailure)
        assertTrue(closed)
    }
    @Test fun `background cancels active storage IO and does not permit a new request`() {
        val session=RunProtocolFixture.session();var closed=false
        session.runStorageIo(42,java.io.Closeable { closed=true }) { session.onBackground();assertTrue(closed) }
        assertNull(session.runAccountId())
        assertTrue(runCatching { session.runStorageIo(42,java.io.Closeable {}) {} }.isFailure)
    }
}
