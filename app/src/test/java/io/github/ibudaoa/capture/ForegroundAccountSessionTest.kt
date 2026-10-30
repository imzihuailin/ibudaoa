package io.github.ibudaoa.capture

import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.net.URLDecoder
import kotlin.concurrent.thread
import kotlinx.serialization.json.*
import io.github.ibudaoa.diagnostics.DiagnosticCodec
import io.github.ibudaoa.diagnostics.PreparationExport
import org.junit.Assert.*
import org.junit.Test

class ForegroundAccountSessionTest {
    private var clock=1000L
    private fun session()=ForegroundAccountSession { clock }
    private fun request():LocalRequest {
        val values=sortedMapOf("uid" to "42","token" to "FAKE_SECRET_TOKEN","timestamp" to "1793000000","nonce" to "123456")
        val plain=values.entries.joinToString("") { it.key+it.value }+"rDJiNB9j7vD2"
        val sign=MessageDigest.getInstance("MD5").digest(plain.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        val body=(values.entries.joinToString("&") { it.key+"="+it.value }+"&sign="+sign).toByteArray()
        return LocalRequest("POST",ReadOnlyPolicy.USER_PROFILE,listOf("Content-Type" to "application/x-www-form-urlencoded","Cookie" to "FAKE_COOKIE_SECRET"),body)
    }
    private val accepted=AccountReadResult(AccountReadOutcome.READ_ACCEPTED_UNBOUND,1,true,false,AccountReadReason.RESPONSE_ACCOUNT_MISSING)
    private fun connect(s:ForegroundAccountSession):Long {
        s.onForeground()
        val ticket=s.begin()
        s.onBackground() // Handoff to official app is the only background exception.
        val r=request();assertTrue(s.accept(ticket,r));r.body.fill(0)
        s.acquisition(ticket,accepted)
        s.onForeground()
        return ticket
    }
    private class Connection(val reply:String="""{"status":1,"data":{}}""",val action:((LocalRequest)->Unit)?=null):AccountReadConnection {
        var sends=0;var closes=0;var body:ByteArray?=null;var response:ByteArray?=null
        override fun send(request:LocalRequest):ByteArray {
            sends++;body=request.body;action?.invoke(request)
            return reply.toByteArray().also { response=it }
        }
        override fun close() { closes++ }
    }
    @Test fun `accepted material survives source wipe and reads with new signature outside capture`() {
        val s=session();connect(s)
        val c=Connection(action={ r ->
            assertEquals(ReadOnlyPolicy.USER_PROFILE,r.target)
            val fields=r.body.toString(Charsets.UTF_8).split('&').associate {
                val p=it.split('=',limit=2);p[0] to URLDecoder.decode(p[1],"UTF-8")
            }
            assertEquals("1793000100",fields["timestamp"])
            assertEquals("654321",fields["nonce"])
            assertEquals("FAKE_SECRET_TOKEN",fields["token"])
            assertFalse(r.headers.any { it.second=="FAKE_COOKIE_SECRET" })
        })
        s.read(c,1793000100,654321)
        assertEquals(1,c.sends);assertEquals(1,c.closes)
        assertTrue(c.body!!.all { it==0.toByte() });assertTrue(c.response!!.all { it==0.toByte() })
        assertEquals(AccountSessionPhase.READY,s.state.value.phase)
        assertEquals(accepted,s.state.value.lastRead)
        assertEquals(1,s.state.value.reads)
    }
    @Test fun `background preserves login and the next foreground can read it`() {
        val s=session();connect(s);s.onBackground();s.onForeground()
        val c=Connection();s.read(c,1793000100,654321)
        assertEquals(1,c.sends);assertEquals(AccountSessionPhase.READY,s.state.value.phase)
        assertTrue(s.state.value.available)
    }
    @Test fun `handoff must return within two minutes and cannot resurrect expired material`() {
        val s=session();s.onForeground();val ticket=s.begin();s.onBackground()
        clock+=120_000;s.expire()
        assertFalse(s.accept(ticket,request()));s.acquisition(ticket,accepted)
        assertEquals(AccountSessionEnd.EXPIRED,s.state.value.end)
        assertFalse(s.state.value.available)
    }
    @Test fun `login has no artificial fifteen minute deletion limit`() {
        val s=session();connect(s)
        clock+=30L*24*60*60*1000;s.expire()
        val c=Connection();s.read(c,1793000100,654321)
        assertEquals(1,c.sends);assertTrue(s.state.value.available)
    }
    @Test fun `account failure requires reconnect and malformed response is retryable without auto retry`() {
        for(reply in listOf("""{"status":101,"data":{}}""","""{"status":102,"data":{}}""",
            """{"status":1,"data":{"uid":43}}""","""{"status":1,"data":{"uid":null}}""")) {
            val s=session();connect(s);val c=Connection(reply);s.read(c,1793000100,654321)
            assertEquals(1,c.sends)
            assertEquals(if(reply.contains("null"))AccountSessionPhase.RETRYABLE else AccountSessionPhase.RECONNECT_REQUIRED,s.state.value.phase)
            val second=Connection();s.read(second,1793000101,654322)
            assertEquals(if(reply.contains("null"))1 else 0,second.sends)
        }
    }
    @Test fun `late network success after background cannot restore credentials or status`() {
        val s=session();connect(s)
        val entered=CountDownLatch(1);val released=CountDownLatch(1)
        val c=object:AccountReadConnection {
            override fun send(request:LocalRequest):ByteArray { entered.countDown();check(released.await(3,TimeUnit.SECONDS));return """{"status":1,"data":{"uid":42}}""".toByteArray() }
            override fun close() { released.countDown() }
        }
        val worker=thread { s.read(c,1793000100,654321) }
        assertTrue(entered.await(3,TimeUnit.SECONDS));s.onBackground();worker.join(3000)
        assertFalse(worker.isAlive);assertEquals(AccountSessionEnd.BACKGROUND,s.state.value.end)
        assertNull(s.state.value.lastRead);assertTrue(s.state.value.available)
    }
    @Test fun `background pauses requests without deleting authentication`() {
        val s=session();connect(s);s.onBackground()
        val c=Connection();s.read(c,1793000100,654321)
        assertEquals(0,c.sends);assertEquals(AccountSessionPhase.SUSPENDED,s.state.value.phase)
        assertTrue(s.state.value.available)
    }
    @Test fun `replaced or cancelled acquisition rejects late installation`() {
        val s=session();val old=s.begin();val current=s.begin()
        assertFalse(s.accept(old,request()))
        s.acquisition(old,accepted)
        assertEquals(AccountSessionPhase.CONNECTING,s.state.value.phase)
        s.clear();assertFalse(s.accept(current,request()))
    }
    @Test fun `network exceptions wipe buffers but preserve retryable login`() {
        val s=session();connect(s);var body:ByteArray?=null
        val c=object:AccountReadConnection {
            override fun send(request:LocalRequest):ByteArray { body=request.body;throw IllegalStateException("PRIVATE_ERROR") }
            override fun close() {}
        }
        s.read(c,1793000100,654321)
        assertEquals(AccountSessionPhase.RETRYABLE,s.state.value.phase)
        assertTrue(body!!.all { it==0.toByte() });assertFalse(s.state.value.toString().contains("PRIVATE_ERROR"))
    }
    @Test fun `safe exports retain paused read evidence without granting imported session`() {
        val s=session();connect(s);s.read(Connection(),1793000100,654321);s.onBackground()
        val cfg=DiagnosticCodec.decode("""{"school_id":1,"term_id":2,"run_zone_id":3}""")
        val text=PreparationExport.encode(cfg,1,1,null,accountSession=s.state.value)
        val summary=Json.parseToJsonElement(text).jsonObject.getValue("account_session").jsonObject
        assertTrue(summary.getValue("memory_available").jsonPrimitive.boolean)
        assertFalse(summary.getValue("credentials_persisted").jsonPrimitive.boolean)
        assertFalse(summary.getValue("configuration_owner_verified").jsonPrimitive.boolean)
        assertEquals(1,summary.getValue("foreground_reads").jsonPrimitive.int)
        for(secret in listOf("FAKE_SECRET_TOKEN","FAKE_COOKIE_SECRET","Cookie","timestamp","1793000100")) assertFalse(text.contains(secret))
        assertFalse(DiagnosticCodec.encode(DiagnosticCodec.decode(text)).contains("account_session"))
    }
    @Test fun `transport rejects any other endpoint before network IO`() {
        val r=request().copy(target=ReadOnlyPolicy.BEFORE_RUN)
        try { AccountReadTransport().use { it.send(r) };fail("Must reject") } catch(_:IllegalArgumentException) { }
    }
    @Test fun `explicit disconnect zeroes the retained backing bytes`() {
        val s=session();connect(s)
        val field=ForegroundAccountSession::class.java.getDeclaredField("material").apply { isAccessible=true }
        val bytes=field.get(s) as ByteArray
        assertTrue(bytes.any { it!=0.toByte() });s.clear()
        assertTrue(bytes.all { it==0.toByte() });assertNull(field.get(s))
        assertEquals(AccountSessionEnd.DISCONNECTED,s.state.value.end)
    }
    @Test fun `capture cleanup before completion clears partially installed credentials`() {
        val s=session();val ticket=s.begin();assertTrue(s.accept(ticket,request()))
        s.captureEnded(ticket);s.acquisition(ticket,accepted)
        assertFalse(s.state.value.available)
        assertEquals(AccountSessionEnd.CAPTURE_FAILED,s.state.value.end)
        val finished=session();val finishedTicket=connect(finished);finished.captureEnded(finishedTicket)
        assertTrue(finished.state.value.available)
    }
    @Test fun `return before acquisition then leaving rejects a later acquisition`() {
        val s=session();s.onForeground();val ticket=s.begin();s.onBackground();s.onForeground();s.onBackground()
        assertFalse(s.accept(ticket,request()));s.acquisition(ticket,accepted)
        assertEquals(AccountSessionEnd.CAPTURE_FAILED,s.state.value.end)
    }
    @Test fun `concurrent reads cannot issue a second request or overwrite replacement`() {
        val s=session();connect(s)
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val first=object:AccountReadConnection {
            override fun send(request:LocalRequest):ByteArray { entered.countDown();check(release.await(3,TimeUnit.SECONDS));return """{"status":1,"data":{}}""".toByteArray() }
            override fun close() { release.countDown() }
        }
        val worker=thread { s.read(first,1793000100,654321) }
        assertTrue(entered.await(3,TimeUnit.SECONDS))
        val second=Connection();s.read(second,1793000101,654322);assertEquals(0,second.sends)
        s.begin();worker.join(3000)
        assertFalse(worker.isAlive);assertEquals(AccountSessionPhase.CONNECTING,s.state.value.phase)
        assertNull(s.state.value.lastRead)
    }
}
