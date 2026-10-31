package io.github.ibudaoa.capture

import java.io.IOException
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.IvParameterSpec
import java.net.URLEncoder
import java.util.Base64
import kotlinx.serialization.json.*
import io.github.ibudaoa.diagnostics.DiagnosticCodec
import org.junit.Assert.*
import org.junit.Test

private object MaintenanceFixture {
    val config="""{"school_id":1,"term_id":2,"run_zone_id":3,"run_face_verify_config":{}}"""
    val success="""{"status":1,"data":{}}"""
    val expired="""{"status":101,"data":{}}"""
    val rotated="""{"status":1,"data":{"uid":42,"access_token":"FAKE_TOKEN_B","refresh_token":"FAKE_REFRESH_B"}}"""
    fun material(refresh:Boolean=true):ByteArray=buildJsonObject {
        put("schema",1);put("strip_spaces",false)
        put("parameters",buildJsonObject {
            for((k,v) in mapOf("uid" to "42","token" to "FAKE_TOKEN_A","school_id" to "1","version" to "431","version_name" to "4.3.1",
                "mobileModel" to "Dummy Model","mobileDeviceId" to "FAKE_DEVICE","mobileOsVersion" to "16","ostype" to "1")) put(k,v)
        })
        put("umid","A".repeat(32))
        if(refresh) { put("refresh_token","FAKE_REFRESH_A");put("refresh_state","AVAILABLE") }
    }.toString().toByteArray()
    class Store(initial:ByteArray=material()):AccountSessionStorage {
        var bytes:ByteArray?=initial.clone()
        var failOn:String?=null
        val states=mutableListOf<String>()
        override fun load()=bytes?.clone()
        override fun save(plain:ByteArray) {
            val state=AccountProtocol.refreshState(plain)
            if(state==failOn)throw IOException("FAKE_STORAGE_FAILURE")
            bytes?.fill(0);bytes=plain.clone();states.add(state)
        }
        override fun delete() { bytes?.fill(0);bytes=null }
    }
    fun session(store:Store)=ForegroundAccountSession().apply { attachStorage(store);restore();onForeground() }
    class Connection(val block:(LocalRequest)->String):AccountReadConnection {
        val paths=mutableListOf<String>()
        override fun send(request:LocalRequest):ByteArray { paths.add(request.target);return block(request).toByteArray() }
        override fun close() {}
    }
    fun fresh()="""{"status":1,"data":$config}"""
    // Independent encoding helper for malformed-signature fixtures, public protocol constants only.
    fun encrypted(values:Map<String,String>,path:String=ReadOnlyPolicy.BEFORE_RUN):LocalRequest {
        val cipher=Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE,SecretKeySpec("goMEzJ1pDpncNHb9".toByteArray(),"AES"),IvParameterSpec("ZUrAS2lUidjyY2gK".toByteArray()))
        val data=JsonObject(values.mapValues { JsonPrimitive(it.value) }).toString().toByteArray()
        val b64=Base64.getEncoder().encodeToString(cipher.doFinal(data))
        return LocalRequest("POST",path,listOf("Content-Type" to "application/x-www-form-urlencoded"),("key="+URLEncoder.encode(b64,"UTF-8")).toByteArray())
    }
}

class AccountMaintenanceTest {
    private val f=MaintenanceFixture
    @Test fun `encrypted beforeRun matches independently generated frozen signature vector`() {
        val request=AccountProtocol.request(f.material(),ReadOnlyPolicy.BEFORE_RUN,1793000100,654321)
        val p=AccountProtocol.decodeRequest(request)
        assertEquals("588fe582187c4302fb4186126e2d04d6",p["sign"])
        assertEquals("1793000100",p["timestamp"]);assertEquals("654321",p["nonce"])
        assertEquals(p["sign"],AccountProtocol.sign(p+("mobileDeviceId" to "DIFFERENT_DEVICE")))
        assertFalse(p.containsKey("refresh_token"));assertFalse(p.containsKey("access_token"))
        assertEquals(2,p.getValue("lpu3").count { it=='\n' })
    }
    @Test fun `official device context and current rotated pair bind only to matching credentials`() {
        val source=f.material();val request=AccountProtocol.request(source,AccountProtocol.REFRESH,1793000100,654321)
        val captured=AccountProtocol.capture(request,f.rotated.toByteArray())
        val merged=AccountProtocol.merge(source,captured)!!
        val root=Json.parseToJsonElement(merged.toString(Charsets.UTF_8)).jsonObject
        assertEquals("FAKE_TOKEN_B",root.getValue("parameters").jsonObject.getValue("token").jsonPrimitive.content)
        assertEquals("FAKE_REFRESH_B",root.getValue("refresh_token").jsonPrimitive.content)
        assertEquals("A".repeat(32),root.getValue("umid").jsonPrimitive.content)
        assertFalse(root.containsKey("origin_token"))
        val mismatch=source.toString(Charsets.UTF_8).replace("FAKE_TOKEN_A","UNRELATED_TOKEN").toByteArray()
        assertNull(AccountProtocol.merge(mismatch,captured))
        val wrongUser=source.toString(Charsets.UTF_8).replace("\"42\"","\"43\"").toByteArray()
        assertNull(AccountProtocol.merge(wrongUser,captured))
    }
    @Test fun `rotation observed before User profile can bind to the already new token`() {
        val source=f.material();val captured=AccountProtocol.capture(AccountProtocol.request(source,AccountProtocol.REFRESH,1793000100,654321),f.rotated.toByteArray())
        val projected=AccountReadProbe.projectForStorage(AccountReadProbe.restoreFromStorage(
            source.toString(Charsets.UTF_8).replace("FAKE_TOKEN_A","FAKE_TOKEN_B").toByteArray()))
        val merged=AccountProtocol.merge(projected,captured)!!
        assertTrue(AccountProtocol.ready(merged));assertTrue(AccountProtocol.hasRefresh(merged))
    }
    @Test fun `bad request signatures device proofs endpoints and account responses cannot seed maintenance`() {
        val source=f.material();val request=AccountProtocol.request(source,ReadOnlyPolicy.BEFORE_RUN,1793000100,654321)
        val values=AccountProtocol.decodeRequest(request)
        val badSign=f.encrypted(values+("sign" to "0".repeat(32)))
        assertTrue(runCatching { AccountProtocol.capture(badSign,f.fresh().toByteArray()) }.isFailure)
        val altered=(values+("lpu3" to "not-a-proof")).toMutableMap();altered["sign"]=AccountProtocol.sign(altered)
        assertTrue(runCatching { AccountProtocol.capture(f.encrypted(altered),f.fresh().toByteArray()) }.isFailure)
        assertTrue(runCatching { AccountProtocol.capture(request.copy(target="/v3/api.php/Run/stopRunV278"),f.fresh().toByteArray()) }.isFailure)
        assertTrue(runCatching { AccountProtocol.capture(request,f.expired.toByteArray()) }.isFailure)
        assertTrue(runCatching { AccountProtocol.capture(request,"""{"status":1,"data":{"uid":43}}""".toByteArray()) }.isFailure)
    }
    @Test fun `refresh success requires a complete new pair and cannot reuse a consumed token`() {
        for(reply in listOf("""{"status":1,"data":{"access_token":"FAKE_TOKEN_B"}}""",
            """{"status":1,"data":{"access_token":"FAKE_TOKEN_B","refresh_token":"FAKE_REFRESH_A"}}""",
            """{"status":1,"data":{"uid":43,"access_token":"FAKE_TOKEN_B","refresh_token":"FAKE_REFRESH_B"}}"""))
            assertTrue(runCatching { AccountProtocol.rotated(f.material(),reply.toByteArray()) }.isFailure)
    }
    @Test fun `without observed device binding encrypted requests are never guessed`() {
        val source=f.material().toString(Charsets.UTF_8)
        val root=Json.parseToJsonElement(source).jsonObject
        val missing=JsonObject(root-"umid").toString().toByteArray()
        assertFalse(AccountProtocol.ready(missing))
        assertTrue(runCatching { AccountProtocol.request(missing,ReadOnlyPolicy.BEFORE_RUN,1793000100,654321) }.isFailure)
        assertTrue(runCatching { AccountProtocol.request(f.material(),"/v3/api.php/My/getSignupPoints",1793000100,654321) }.isFailure)
    }
    @Test fun `full expired login flow rotates once saves before use then reads latest config`() {
        val store=MaintenanceFixture.Store();val session=f.session(store);var userReads=0
        val c=MaintenanceFixture.Connection { request -> when(request.target) {
            ReadOnlyPolicy.USER_PROFILE -> if(userReads++==0) f.expired else {
                assertTrue(request.body.toString(Charsets.UTF_8).contains("FAKE_TOKEN_B"))
                assertEquals("UNVERIFIED",AccountProtocol.refreshState(store.bytes!!));f.success
            }
            AccountProtocol.REFRESH -> { assertEquals("IN_FLIGHT",AccountProtocol.refreshState(store.bytes!!));f.rotated }
            ReadOnlyPolicy.BEFORE_RUN -> { assertEquals("AVAILABLE",AccountProtocol.refreshState(store.bytes!!));f.fresh() }
            else -> error("Unexpected endpoint")
        } }
        session.read(c,1793000100,654321,DiagnosticCodec.decode(f.config))
        assertEquals(listOf(ReadOnlyPolicy.USER_PROFILE,AccountProtocol.REFRESH,ReadOnlyPolicy.USER_PROFILE,ReadOnlyPolicy.BEFORE_RUN),c.paths)
        assertEquals(listOf("IN_FLIGHT","UNVERIFIED","AVAILABLE"),store.states)
        val s=session.state.value
        assertEquals(AccountRefreshOutcome.VERIFIED,s.refreshOutcome);assertEquals(1,s.refreshAttempts)
        assertEquals(LivePreparationPhase.MATCHED,s.preparation.phase)
    }
    @Test fun `expired preparation renews even when profile still accepts login`() {
        val store=MaintenanceFixture.Store();val session=f.session(store);var preparations=0
        val c=MaintenanceFixture.Connection { request -> when(request.target) {
            ReadOnlyPolicy.USER_PROFILE -> f.success
            ReadOnlyPolicy.BEFORE_RUN -> if(preparations++==0) f.expired else f.fresh()
            AccountProtocol.REFRESH -> f.rotated
            else -> error("Unexpected endpoint")
        } }
        session.read(c,1793000100,654321,DiagnosticCodec.decode(f.config))
        assertEquals(1,c.paths.count { it==AccountProtocol.REFRESH })
        assertEquals(2,preparations)
        assertEquals(AccountSessionPhase.READY,session.state.value.phase)
        assertEquals(AccountRefreshOutcome.VERIFIED,session.state.value.refreshOutcome)
        assertNotNull(session.verifiedSchool(1793000100))
    }
    @Test fun `valid login skips refresh and reads online config without replacing cached data`() {
        val store=MaintenanceFixture.Store();val s=f.session(store)
        val cached=DiagnosticCodec.decode(f.config)
        val c=MaintenanceFixture.Connection { if(it.target==ReadOnlyPolicy.USER_PROFILE) f.success else f.fresh() }
        s.read(c,1793000100,654321,cached)
        assertEquals(listOf(ReadOnlyPolicy.USER_PROFILE,ReadOnlyPolicy.BEFORE_RUN),c.paths)
        assertEquals(0,s.state.value.refreshAttempts);assertEquals(0,store.states.size)
        assertEquals(LivePreparationPhase.MATCHED,s.state.value.preparation.phase)
        val report=AccountSessionReport.summary(s.state.value).toString()
        assertTrue(report.contains("\"can_start\":false"));assertTrue(report.contains("\"platform_task_state_verified\":false"))
        assertEquals(DiagnosticCodec.decode(f.config),cached)
    }
    @Test fun `network loss during renewal never automatically reuses the same refresh after restart`() {
        val store=MaintenanceFixture.Store();val s=f.session(store)
        val c=MaintenanceFixture.Connection { if(it.target==ReadOnlyPolicy.USER_PROFILE)f.expired else throw IOException("FAKE_TIMEOUT") }
        s.read(c,1793000100,654321)
        assertEquals("IN_FLIGHT",AccountProtocol.refreshState(store.bytes!!))
        assertEquals(1,c.paths.count { it==AccountProtocol.REFRESH })
        val restored=f.session(store);val again=MaintenanceFixture.Connection { f.expired }
        restored.read(again,1793000101,654322)
        assertEquals(listOf(ReadOnlyPolicy.USER_PROFILE),again.paths)
        assertEquals(AccountRefreshOutcome.UNCONFIRMED,restored.state.value.refreshOutcome)
    }
    @Test fun `cannot send refresh if the in flight marker cannot be saved`() {
        val store=MaintenanceFixture.Store();store.failOn="IN_FLIGHT";val s=f.session(store)
        val c=MaintenanceFixture.Connection { f.expired };s.read(c,1793000100,654321)
        assertEquals(listOf(ReadOnlyPolicy.USER_PROFILE),c.paths)
        assertEquals("AVAILABLE",AccountProtocol.refreshState(store.bytes!!))
        assertEquals(AccountSessionPhase.STORAGE_ERROR,s.state.value.phase)
    }
    @Test fun `failed save after rotation retries the commit without another renewal request`() {
        val store=MaintenanceFixture.Store();store.failOn="UNVERIFIED";val s=f.session(store)
        val c=MaintenanceFixture.Connection { if(it.target==ReadOnlyPolicy.USER_PROFILE)f.expired else f.rotated }
        s.read(c,1793000100,654321)
        assertEquals(AccountSessionPhase.STORAGE_ERROR,s.state.value.phase)
        assertEquals("IN_FLIGHT",AccountProtocol.refreshState(store.bytes!!))
        store.failOn=null;s.restore()
        val next=MaintenanceFixture.Connection { if(it.target==ReadOnlyPolicy.USER_PROFILE) {
            assertTrue(it.body.toString(Charsets.UTF_8).contains("FAKE_TOKEN_B"));f.success
        } else f.fresh() }
        s.read(next,1793000101,654322,DiagnosticCodec.decode(f.config))
        assertFalse(next.paths.contains(AccountProtocol.REFRESH))
        assertEquals(AccountRefreshOutcome.VERIFIED,s.state.value.refreshOutcome)
        assertEquals(LivePreparationPhase.MATCHED,s.state.value.preparation.phase)
    }
    @Test fun `completed rotation after foreground loss is saved but does not continue background requests`() {
        val store=MaintenanceFixture.Store();val s=f.session(store)
        val c=MaintenanceFixture.Connection { if(it.target==ReadOnlyPolicy.USER_PROFILE)f.expired else { s.onBackground();f.rotated } }
        s.read(c,1793000100,654321)
        assertEquals(listOf(ReadOnlyPolicy.USER_PROFILE,AccountProtocol.REFRESH),c.paths)
        assertEquals("UNVERIFIED",AccountProtocol.refreshState(store.bytes!!))
        assertEquals(AccountSessionPhase.SUSPENDED,s.state.value.phase)
        s.onForeground()
        val next=MaintenanceFixture.Connection { if(it.target==ReadOnlyPolicy.USER_PROFILE) f.success else f.fresh() }
        s.read(next,1793000101,654322,DiagnosticCodec.decode(f.config))
        assertFalse(next.paths.contains(AccountProtocol.REFRESH))
        assertEquals(LivePreparationPhase.MATCHED,s.state.value.preparation.phase)
    }
    @Test fun `logout during rotation prevents late success from resurrecting saved credentials`() {
        val store=MaintenanceFixture.Store();val s=f.session(store)
        val c=MaintenanceFixture.Connection { if(it.target==ReadOnlyPolicy.USER_PROFILE)f.expired else { s.clear();f.rotated } }
        s.read(c,1793000100,654321)
        assertNull(store.bytes);assertEquals(AccountSessionPhase.CLEARED,s.state.value.phase)
    }
    @Test fun `invalid rotated pair cannot trigger repeat renewal or use old credentials for preparation`() {
        val store=MaintenanceFixture.Store();val s=f.session(store)
        val c=MaintenanceFixture.Connection { if(it.target==ReadOnlyPolicy.USER_PROFILE)f.expired else """{"status":1,"data":{}}""" }
        s.read(c,1793000100,654321)
        assertEquals(2,c.paths.size);assertEquals("IN_FLIGHT",AccountProtocol.refreshState(store.bytes!!))
        assertEquals(AccountRefreshOutcome.UNCONFIRMED,s.state.value.refreshOutcome)
    }
    @Test fun `same current account can acquire protocol context before or after profile validation`() {
        for(early in listOf(true,false)) {
            val store=MaintenanceFixture.Store();store.bytes=null
            val s=f.session(store);val ticket=s.begin();s.onBackground()
            val context=AccountProtocol.capture(AccountProtocol.request(f.material(),AccountProtocol.REFRESH,1793000100,654321),f.rotated.toByteArray())
            val newSource=f.material().toString(Charsets.UTF_8).replace("FAKE_TOKEN_A","FAKE_TOKEN_B").toByteArray()
            if(early)s.offerProtocol(ticket,context)
            val r=AccountReadProbe.restoreFromStorage(newSource);s.accept(ticket,r);r.body.fill(0)
            s.acquisition(ticket,AccountReadResult(AccountReadOutcome.READ_ACCEPTED_UNBOUND,1,true,false,AccountReadReason.RESPONSE_ACCOUNT_MISSING))
            if(!early)s.offerProtocol(ticket,context)
            context.fill(0)
            assertTrue(s.state.value.protocolReady);assertTrue(s.state.value.refreshAvailable)
            assertEquals("FAKE_REFRESH_B",Json.parseToJsonElement(store.bytes!!.toString(Charsets.UTF_8)).jsonObject.getValue("refresh_token").jsonPrimitive.content)
        }
    }
    @Test fun `online configuration differences are scoped and cannot silently overwrite old roads`() {
        val store=MaintenanceFixture.Store();val s=f.session(store)
        val c=MaintenanceFixture.Connection { if(it.target==ReadOnlyPolicy.USER_PROFILE)f.success else f.fresh().replace("\"run_zone_id\":3","\"run_zone_id\":4") }
        s.read(c,1793000100,654321,DiagnosticCodec.decode(f.config))
        assertEquals(LivePreparationPhase.CHANGED,s.state.value.preparation.phase)
        assertEquals(setOf(PreparationSection.IDENTITY),s.state.value.preparation.changed)
    }
    @Test fun `different school and malformed live responses cannot grant verified configuration`() {
        for(reply in listOf(f.fresh().replace("\"school_id\":1","\"school_id\":9"),"not-json")) {
            val s=f.session(MaintenanceFixture.Store());val c=MaintenanceFixture.Connection { if(it.target==ReadOnlyPolicy.USER_PROFILE)f.success else reply }
            s.read(c,1793000100,654321,DiagnosticCodec.decode(f.config))
            assertEquals(LivePreparationPhase.FAILED,s.state.value.preparation.phase)
            assertFalse(AccountSessionReport.summary(s.state.value).getValue("configuration_owner_verified").jsonPrimitive.boolean)
        }
    }
    @Test fun `reports never contain auth device fields or raw protocol responses`() {
        val s=f.session(MaintenanceFixture.Store());val c=MaintenanceFixture.Connection { if(it.target==ReadOnlyPolicy.USER_PROFILE)f.success else f.fresh() }
        s.read(c,1793000100,654321,DiagnosticCodec.decode(f.config))
        val report=AccountSessionReport.summary(s.state.value).toString()
        for(text in listOf("FAKE_TOKEN","FAKE_REFRESH","FAKE_DEVICE","umid","lpu3","access_token","parameters","school_id")) assertFalse(report.contains(text))
    }
    @Test fun `export after background retains last online result but invalidates current authorization`() {
        val s=f.session(MaintenanceFixture.Store());val c=MaintenanceFixture.Connection { if(it.target==ReadOnlyPolicy.USER_PROFILE)f.success else f.fresh() }
        s.read(c,1793000100,654321,DiagnosticCodec.decode(f.config));s.onBackground()
        val report=AccountSessionReport.summary(s.state.value)
        assertFalse(report.getValue("configuration_owner_verified").jsonPrimitive.boolean)
        val live=report.getValue("live_preparation").jsonObject
        assertEquals("MATCHED",live.getValue("phase").jsonPrimitive.content)
        assertFalse(live.getValue("current").jsonPrimitive.boolean)
    }
    @Test fun `failed validation marker save can be repaired without consuming another refresh token`() {
        val store=MaintenanceFixture.Store();store.failOn="AVAILABLE";val s=f.session(store);var reads=0
        val c=MaintenanceFixture.Connection { if(it.target==ReadOnlyPolicy.USER_PROFILE) {
            if(reads++==0)f.expired else f.success
        } else f.rotated }
        s.read(c,1793000100,654321)
        assertEquals(AccountSessionPhase.STORAGE_ERROR,s.state.value.phase)
        assertEquals("UNVERIFIED",AccountProtocol.refreshState(store.bytes!!))
        store.failOn=null;s.restore()
        val next=MaintenanceFixture.Connection { if(it.target==ReadOnlyPolicy.USER_PROFILE)f.success else f.fresh() }
        s.read(next,1793000101,654322,DiagnosticCodec.decode(f.config))
        assertFalse(next.paths.contains(AccountProtocol.REFRESH));assertEquals("AVAILABLE",AccountProtocol.refreshState(store.bytes!!))
        assertEquals(LivePreparationPhase.MATCHED,s.state.value.preparation.phase)
    }
    @Test fun `explicit server rejection of refresh is never retried automatically`() {
        val store=MaintenanceFixture.Store();val s=f.session(store)
        val c=MaintenanceFixture.Connection { f.expired };s.read(c,1793000100,654321)
        assertEquals(listOf(ReadOnlyPolicy.USER_PROFILE,AccountProtocol.REFRESH),c.paths)
        assertEquals(AccountRefreshOutcome.REJECTED,s.state.value.refreshOutcome)
        val next=f.session(store);val again=MaintenanceFixture.Connection { f.expired }
        next.read(again,1793000101,654322)
        assertEquals(listOf(ReadOnlyPolicy.USER_PROFILE),again.paths)
    }
    @Test fun `a missing renewal credential cannot be fabricated from ordinary login`() {
        val s=f.session(MaintenanceFixture.Store(f.material(refresh=false)))
        val c=MaintenanceFixture.Connection { f.expired };s.read(c,1793000100,654321)
        assertEquals(listOf(ReadOnlyPolicy.USER_PROFILE),c.paths)
        assertEquals(AccountRefreshOutcome.NOT_AVAILABLE,s.state.value.refreshOutcome)
    }
    @Test fun `verified school grant is private current short lived and invalid after leaving foreground`() {
        val s=f.session(MaintenanceFixture.Store())
        val c=MaintenanceFixture.Connection { if(it.target==ReadOnlyPolicy.USER_PROFILE)f.success else f.fresh() }
        s.read(c,1793000100,654321,DiagnosticCodec.decode(f.config))
        val grant=s.verifiedSchool(1793000100)!!
        assertTrue(s.isCurrent(grant,1793000100));assertFalse(s.isCurrent(grant,1793000221))
        val forged=VerifiedSchoolConfig(grant.config,grant.baseline,grant.issuedAt,grant.revision)
        assertFalse(s.isCurrent(forged,1793000100))
        s.onBackground();assertFalse(s.isCurrent(grant,1793000100))
        assertNull(s.verifiedSchool(1793000100))
    }
    @Test fun `failed online response cannot reuse an earlier successful school grant`() {
        val s=f.session(MaintenanceFixture.Store())
        s.read(MaintenanceFixture.Connection { if(it.target==ReadOnlyPolicy.USER_PROFILE)f.success else f.fresh() },1793000100,654321)
        val old=s.verifiedSchool(1793000100)!!
        s.read(MaintenanceFixture.Connection { if(it.target==ReadOnlyPolicy.USER_PROFILE)f.success else "bad" },1793000101,654322)
        assertNull(s.verifiedSchool(1793000101));assertFalse(s.isCurrent(old,1793000101))
    }

    @Test fun `temporary captured account binds school only after encrypted material is saved`() {
        val store=MaintenanceFixture.Store(); val session=f.session(store);val ticket=session.begin()
        val request=AccountReadProbe.restoreFromStorage(f.material())
        assertFalse(session.captureReady(ticket))
        assertTrue(session.accept(ticket,request));request.body.fill(0)
        session.acquisition(ticket,AccountReadResult(AccountReadOutcome.READ_ACCEPTED_UNBOUND,1,true))
        assertTrue(session.captureReady(ticket))
        assertTrue(session.captureSchoolMatches(ticket,DiagnosticCodec.decode(f.config)))
        assertFalse(session.captureSchoolMatches(ticket,DiagnosticCodec.decode(f.config.replace("\"school_id\":1","\"school_id\":2"))))
        val context=AccountProtocol.capture(AccountProtocol.request(f.material(),ReadOnlyPolicy.BEFORE_RUN,1793000100,654321),f.fresh().toByteArray())
        assertTrue(session.captureContextMatches(ticket,context))
        session.invalidateCapture(ticket)
        assertFalse(session.captureReady(ticket));assertFalse(session.captureContextMatches(ticket,context))
        context.fill(0)
    }
    @Test fun `cancelled acquisition cannot replace persisted login with late profile response`() {
        val store=MaintenanceFixture.Store();val session=f.session(store);val before=store.bytes!!.clone()
        val ticket=session.begin();session.invalidateCapture(ticket)
        val request=AccountReadProbe.restoreFromStorage(f.material())
        assertFalse(session.accept(ticket,request));request.body.fill(0)
        session.acquisition(ticket,AccountReadResult(AccountReadOutcome.READ_ACCEPTED_UNBOUND,1,true))
        assertArrayEquals(before,store.bytes)
    }
    @Test fun `acquisition school read is exact endpoint and late invalidation fences result`() {
        val store=MaintenanceFixture.Store();val session=f.session(store);val ticket=session.begin()
        val request=AccountReadProbe.restoreFromStorage(f.material());session.accept(ticket,request);request.body.fill(0)
        session.acquisition(ticket,AccountReadResult(AccountReadOutcome.READ_ACCEPTED_UNBOUND,1,true))
        val transport=MaintenanceFixture.Connection { assertEquals(ReadOnlyPolicy.BEFORE_RUN,it.target);session.invalidateCapture(ticket);f.fresh() }
        assertNull(session.readCaptureSchool(ticket,transport,1793000100,654321))
        assertEquals(1,transport.paths.size)
    }

}
