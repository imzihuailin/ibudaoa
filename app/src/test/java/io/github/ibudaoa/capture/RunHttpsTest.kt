package io.github.ibudaoa.capture

import io.github.ibudaoa.running.*
import java.net.URL
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

internal class FakeRunHttps(url:URL,private val status:Int=200,private val reply:ByteArray="{}".toByteArray(),
    private val etag:Boolean=false):HttpsURLConnection(url) {
    val body=ByteArrayOutputStream();var disconnected=false
    override fun getOutputStream()=body
    override fun getInputStream()=ByteArrayInputStream(reply)
    override fun getResponseCode()=status
    override fun getHeaderField(name:String):String?=if(name.equals("ETag",true) && etag)"\"${UploadFixture.etag(body.toByteArray())}\"" else null
    override fun connect() {}
    override fun disconnect() { disconnected=true }
    override fun usingProxy()=false
    override fun getCipherSuite()="synthetic-test"
    override fun getLocalCertificates():Array<Certificate>?=null
    override fun getServerCertificates():Array<Certificate> = emptyArray()
}
class RunHttpsTest {
    @Test fun `API transport pins host disables redirects and never retries non success`() {
        val request=RunProtocol.request(RunProtocolFixture.material(),RunEndpoint.ZONE,UploadFixture.context,mapOf("run_zone_id" to "3"),RunProtocolFixture.now,123456)
        var attempts=0;var client:FakeRunHttps?=null
        val transport=RunApiHttpsTransport { url ->
            attempts++;assertEquals("api2.lptiyu.com",url.host);assertEquals("https",url.protocol)
            FakeRunHttps(url,302).also { client=it }
        }
        assertTrue(runCatching { transport.send(request) }.isFailure)
        assertEquals(1,attempts);assertFalse(client!!.instanceFollowRedirects)
        assertEquals("POST",client!!.requestMethod);assertTrue(client!!.disconnected)
        assertEquals(10_000,client!!.connectTimeout);assertEquals(15_000,client!!.readTimeout)
    }
    @Test fun `API transport refuses unlisted paths and header injection before opening a socket`() {
        var opened=false;val transport=RunApiHttpsTransport { opened=true;FakeRunHttps(it) }
        val type=listOf("Content-Type" to "application/x-www-form-urlencoded")
        assertTrue(runCatching { transport.send(LocalRequest("POST","/v3/api.php/Run/unverified",type,"x=1".toByteArray())) }.isFailure)
        assertTrue(runCatching { transport.send(LocalRequest("POST",RunEndpoint.ZONE.path,type+listOf("Host" to "other.test"),"x=1".toByteArray())) }.isFailure)
        assertTrue(runCatching { transport.send(LocalRequest("POST",RunEndpoint.ZONE.path,type+listOf("User-Agent" to "x\r\nBad: yes"),"x=1".toByteArray())) }.isFailure)
        assertFalse(opened)
    }
    @Test fun `object upload is HTTPS single PUT and receipt is bound to exact bytes and provider acknowledgement`() {
        val grant=RunStorageGrant.decode(UploadFixture.grant(),UploadFixture.now)
        var client:FakeRunHttps?=null
        UploadFixture.archive().use { archive ->
            RunObjectUpload { url ->
                assertEquals("https",url.protocol);assertEquals("synthetic-run-data.oss-cn-hangzhou.aliyuncs.com",url.host)
                FakeRunHttps(url,etag=true).also { client=it }
            }.use { upload ->
                val receipt=upload.upload(grant,archive,UploadFixture.now)
                assertEquals(archive.sha256,receipt.archiveSha256);assertTrue(receipt.contains(UploadFixture.points.first()))
            }
        }
        assertEquals("PUT",client!!.requestMethod);assertFalse(client!!.instanceFollowRedirects);assertTrue(client!!.disconnected)
    }
    @Test fun `object redirect or missing ETag cannot produce upload success and is not retried`() {
        val grant=RunStorageGrant.decode(UploadFixture.grant(),UploadFixture.now)
        for(code in listOf(302,403,200)) {
            var attempts=0
            UploadFixture.archive().use { archive ->
                RunObjectUpload { attempts++;FakeRunHttps(it,code) }.use {
                    assertTrue(runCatching { it.upload(grant,archive,UploadFixture.now) }.isFailure)
                }
            }
            assertEquals(1,attempts)
        }
    }
    @Test fun `authenticated adapter assembles signed requests upload and exact record query without live network`() = runBlocking {
        val session=RunProtocolFixture.session();val sent=mutableListOf<Pair<RunEndpoint,Map<String,String>>>()
        var puts=0
        val gateway=AuthenticatedRunGateway(session,UploadFixture.config,{true},
            { RunFinishMeasurements(60,listOf(60),kotlinx.serialization.json.JsonArray(emptyList())) },
            now={UploadFixture.now},nonce={123456},connection={object:AccountReadConnection {
                override fun send(request:LocalRequest):ByteArray {
                    val endpoint=RunEndpoint.entries.single { it.path==request.target }
                    sent.add(endpoint to RunProtocolFixture.fields(request))
                    return when(endpoint) {
                        RunEndpoint.STORAGE -> UploadFixture.grant()
                        else -> """{"status":1,"data":{}}""".toByteArray()
                    }
                }
                override fun close() {}
            }},uploader={RunObjectUpload { puts++;FakeRunHttps(it,etag=true) }})
        val ctx=UploadFixture.context;val progress=UploadFixture.progress
        gateway.write(RunWrite.SELECT_ZONE,ctx,null,progress,emptyList(),emptyList())
        gateway.write(RunWrite.REGISTER,ctx,null,progress,emptyList(),emptyList())
        gateway.write(RunWrite.LOCATION,ctx,UploadFixture.trace.last().fix,progress,UploadFixture.trace,UploadFixture.points)
        gateway.write(RunWrite.FINISH,ctx,null,progress,UploadFixture.trace,UploadFixture.points)
        gateway.record(ExpectedRunRecord(99,42,3,1700000000))
        assertEquals(listOf(RunEndpoint.ZONE,RunEndpoint.REGISTER,RunEndpoint.LOCATION,RunEndpoint.STORAGE,RunEndpoint.FINISH,RunEndpoint.RECORD),sent.map { it.first })
        assertEquals(1,puts);assertEquals("99",sent.last().second["record_id"])
        val finish=sent.single { it.first==RunEndpoint.FINISH }.second
        assertTrue(finish.getValue("record_file").startsWith("Public/Upload/file/run_record/2026-10-29/"))
        assertEquals("111c40ba201981fd30dd55da40bb2109",finish["record_str"])
        assertFalse(finish.containsKey("refresh_token"))
    }
    @Test fun `incomplete sensor finish readiness blocks start before any request`() = runBlocking {
        var attempted=false
        val gateway=AuthenticatedRunGateway(RunProtocolFixture.session(),UploadFixture.config,{false},{error("No measured sensors")},
            connection={attempted=true;error("Should not open")})
        assertTrue(runCatching { gateway.write(RunWrite.SELECT_ZONE,UploadFixture.context,null,UploadFixture.progress,emptyList(),emptyList()) }.isFailure)
        assertFalse(attempted)
    }
    @Test fun `recovered pending record can be queried with its saved context without another finish`() = runBlocking {
        var path:String?=null
        val gateway=AuthenticatedRunGateway(RunProtocolFixture.session(),UploadFixture.config,{false},{error("No new run")},
            connection={object:AccountReadConnection {
                override fun send(request:LocalRequest):ByteArray { path=request.target;return "{}".toByteArray() }
                override fun close() {}
            }},restoredContext=UploadFixture.context)
        gateway.record(ExpectedRunRecord(99,42,3,1700000000))
        assertEquals(RunEndpoint.RECORD.path,path)
    }
}
