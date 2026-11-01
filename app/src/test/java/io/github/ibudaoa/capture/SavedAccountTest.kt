package io.github.ibudaoa.capture

import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SavedAccountTest {
    private fun key()=KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private class Store:AccountSessionStorage {
        val key:SecretKey=KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        var sealed:ByteArray?=null
        var failLoad=false;var failSave=false;var saves=0;var deletes=0
        override fun load():ByteArray? { if(failLoad) throw IOException("PRIVATE_ERROR");return sealed?.let { AccountStorageCipher.decrypt(it,key) } }
        override fun save(plain:ByteArray) { if(failSave)throw IOException("PRIVATE_ERROR");val next=AccountStorageCipher.encrypt(plain,key);sealed?.fill(0);sealed=next;saves++ }
        override fun delete() { sealed?.fill(0);sealed=null;deletes++ }
    }
    private val accepted=AccountReadResult(AccountReadOutcome.READ_ACCEPTED_UNBOUND,1,true,false,AccountReadReason.RESPONSE_ACCOUNT_MISSING)
    private fun request(token:String="FAKE_TOKEN_A"):LocalRequest {
        val p=sortedMapOf("uid" to "42","token" to token,"school_id" to "1","timestamp" to "1793000000","nonce" to "123456",
            "mobileDeviceId" to "FAKE_DEVICE_BINDING","mobileModel" to "Dummy Model",
            "student_num" to "DO_NOT_STORE_STUDENT","card_id" to "DO_NOT_STORE_CARD","unknown_private" to "DO_NOT_STORE_UNKNOWN")
        val sign=MessageDigest.getInstance("MD5").digest((p.entries.joinToString("") { it.key+it.value }+"rDJiNB9j7vD2").toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        p["sign"]=sign
        return LocalRequest("POST",ReadOnlyPolicy.USER_PROFILE,listOf("Content-Type" to "application/x-www-form-urlencoded",
            "Cookie" to "DO_NOT_STORE_COOKIE","Authorization" to "DO_NOT_STORE_HEADER","User-Agent" to "DummyAgent/1"),
            p.entries.joinToString("&") { URLEncoder.encode(it.key,"UTF-8")+"="+URLEncoder.encode(it.value,"UTF-8") }.toByteArray())
    }
    private fun connect(s:ForegroundAccountSession,token:String="FAKE_TOKEN_A") {
        s.onForeground();val ticket=s.begin();s.onBackground()
        val request=request(token);assertTrue(s.accept(ticket,request));request.body.fill(0)
        s.acquisition(ticket,accepted);s.onForeground()
    }
    private fun restored(store:Store)=ForegroundAccountSession().apply { attachStorage(store);restore();onForeground() }
    private class Read(val fail:Boolean=false,val inspect:((LocalRequest)->Unit)?=null):AccountReadConnection {
        var count=0
        override fun send(request:LocalRequest):ByteArray { count++;inspect?.invoke(request);if(fail)throw IOException("PRIVATE_ERROR");return """{"status":1,"data":{}}""".toByteArray() }
        override fun close() {}
    }
    @Test fun `profile is encrypted restored excluded from requests and removed on logout`() {
        val store=Store();val session=restored(store);connect(session)
        assertNull(session.state.value.displayName) // Old storage remains usable.
        val client=object:AccountReadConnection {
            override fun send(request:LocalRequest):ByteArray {
                assertFalse(request.body.toString(Charsets.UTF_8).contains("测试用户"))
                return """{"status":1,"data":{"uid":42,"name":"测试用户","school_name":"测试大学"}}""".toByteArray()
            }
            override fun close() {}
        }
        session.read(client,1793000100,654321)
        assertEquals("测试用户",session.state.value.displayName)
        assertFalse(store.sealed!!.toString(Charsets.UTF_8).contains("测试用户"))
        assertEquals("测试用户",restored(store).state.value.displayName)
        assertFalse(AccountSessionReport.summary(session.state.value).toString().contains("测试用户"))
        session.clear();assertNull(session.state.value.displayName)
        assertNull(restored(store).state.value.displayName)
    }
    @Test fun `mismatched account response cannot replace display name`() {
        val store=Store();val session=restored(store);connect(session)
        session.read(object:AccountReadConnection {
            override fun send(request:LocalRequest)="""{"status":1,"data":{"uid":99,"name":"其他用户"}}""".toByteArray()
            override fun close() {}
        },1793000100,654321)
        assertNull(session.state.value.displayName)
        assertNull(restored(store).state.value.displayName)
    }
    @Test fun `replacement credentials do not inherit another accounts display name`() {
        val old=Json.parseToJsonElement(AccountReadProbe.projectForStorage(request()).toString(Charsets.UTF_8)).jsonObject
        val named=JsonObject(old+mapOf("display_name" to JsonPrimitive("原用户"))).toString().toByteArray()
        val newRoot=JsonObject(old+mapOf("parameters" to JsonObject(old.getValue("parameters").jsonObject+mapOf("uid" to JsonPrimitive("99")))))
        val inherited=AccountProtocol.inherit(named,newRoot.toString().toByteArray())
        assertFalse(inherited.toString(Charsets.UTF_8).contains("原用户"))
        val same=AccountProtocol.inherit(named,old.toString().toByteArray())
        assertTrue(same.toString(Charsets.UTF_8).contains("原用户"))
    }
    @Test fun `persistence projection excludes complete requests clocks cookies and unrelated identifiers`() {
        val bytes=AccountReadProbe.projectForStorage(request())
        val text=bytes.toString(Charsets.UTF_8)
        for(secret in listOf("DO_NOT_STORE","timestamp","nonce","sign\"","Cookie","Authorization",ReadOnlyPolicy.USER_PROFILE)) assertFalse(text.contains(secret))
        val r=AccountReadProbe.restoreFromStorage(bytes)
        assertTrue(r.headers.contains("User-Agent" to "DummyAgent/1"))
        assertFalse(r.headers.any { it.first=="Cookie" || it.first=="Authorization" })
        assertTrue(r.body.toString(Charsets.UTF_8).contains("FAKE_TOKEN_A"))
        r.body.fill(0);bytes.fill(0)
    }
    @Test fun `AES GCM round trip uses fresh IVs and ciphertext contains no plaintext`() {
        val plain=AccountReadProbe.projectForStorage(request());val key=key()
        val a=AccountStorageCipher.encrypt(plain,key);val b=AccountStorageCipher.encrypt(plain,key)
        assertFalse(a.contentEquals(b));assertArrayEquals(plain,AccountStorageCipher.decrypt(a,key))
        assertFalse(a.toString(Charsets.ISO_8859_1).contains("FAKE_TOKEN_A"))
        assertFalse(a.toString(Charsets.ISO_8859_1).contains("FAKE_DEVICE_BINDING"))
    }
    @Test fun `tampering with envelope IV or ciphertext is rejected without sensitive errors`() {
        val key=key();val bytes=AccountStorageCipher.encrypt("PRIVATE_PLAINTEXT".toByteArray(),key)
        for(index in listOf(0,4,15,16,bytes.lastIndex)) {
            val changed=bytes.clone();changed[index]=(changed[index].toInt() xor 1).toByte()
            try { AccountStorageCipher.decrypt(changed,key);fail("Must reject") }
            catch(e:AccountStorageInvalid) { assertFalse(e.toString().contains("PRIVATE_PLAINTEXT")) }
        }
    }
    @Test fun `wrong keys truncation and oversized ciphertext cannot restore login`() {
        val bytes=AccountStorageCipher.encrypt("PRIVATE".toByteArray(),key())
        for(data in listOf(bytes,bytes.copyOf(10),ByteArray(AccountStorageCipher.MAX_PLAIN+33))) {
            try { AccountStorageCipher.decrypt(data,key());fail("Must reject") } catch(_:AccountStorageInvalid) {}
        }
    }
    @Test fun `new process restores encrypted credentials as unverified then reads independently`() {
        val store=Store();val first=restored(store);connect(first)
        assertEquals(1,store.saves);assertTrue(first.state.value.credentialsPersisted)
        val second=restored(store)
        assertEquals(AccountSessionPhase.RESTORED,second.state.value.phase)
        assertTrue(second.state.value.restoredFromStorage);assertNull(second.state.value.lastRead)
        val client=Read(inspect={ r ->
            val text=r.body.toString(Charsets.UTF_8)
            assertTrue(text.contains("FAKE_TOKEN_A"));assertTrue(text.contains("1793000100"))
            assertFalse(text.contains("DO_NOT_STORE"))
        })
        second.read(client,1793000100,654321)
        assertEquals(1,client.count);assertEquals(AccountSessionPhase.READY,second.state.value.phase)
        assertTrue(second.state.value.lastRead!!.readAccepted);assertEquals(1,store.saves)
    }
    @Test fun `background and foreground do not rewrite or delete encrypted login`() {
        val store=Store();val s=restored(store);connect(s);val original=store.sealed!!.clone()
        s.onBackground();assertTrue(s.state.value.credentialsPersisted)
        val blocked=Read();s.read(blocked,1793000100,654321);assertEquals(0,blocked.count)
        s.onForeground();assertEquals(AccountSessionPhase.RESTORED,s.state.value.phase)
        s.read(Read(),1793000100,654321)
        assertArrayEquals(original,store.sealed);assertEquals(0,store.deletes)
    }
    @Test fun `network failure is retryable and keeps saved credentials usable after process restart`() {
        val store=Store();val s=restored(store);connect(s);val original=store.sealed!!.clone()
        s.read(Read(fail=true),1793000100,654321)
        assertEquals(AccountSessionPhase.RETRYABLE,s.state.value.phase);assertTrue(s.state.value.credentialsPersisted)
        assertArrayEquals(original,store.sealed);assertEquals(0,store.deletes)
        val second=restored(store);second.read(Read(),1793000101,654322)
        assertTrue(second.state.value.lastRead!!.readAccepted)
    }
    @Test fun `transient storage load error preserves ciphertext and can retry restoration`() {
        val store=Store();connect(restored(store));val original=store.sealed!!.clone()
        store.failLoad=true;val s=restored(store)
        assertEquals(AccountSessionPhase.STORAGE_ERROR,s.state.value.phase)
        assertArrayEquals(original,store.sealed);assertEquals(0,store.deletes)
        store.failLoad=false;s.restore()
        assertEquals(AccountSessionPhase.RESTORED,s.state.value.phase);assertTrue(s.state.value.restoredFromStorage)
    }
    @Test fun `corrupt or incompatible encrypted storage requires repair without leaking or deleting it`() {
        val store=Store();connect(restored(store));store.sealed!![16]=(store.sealed!![16].toInt() xor 1).toByte()
        val original=store.sealed!!.clone();val s=restored(store)
        assertEquals(AccountSessionPhase.RECONNECT_REQUIRED,s.state.value.phase)
        assertEquals(AccountSessionEnd.STORAGE_INVALID,s.state.value.end)
        assertFalse(s.state.value.available);assertArrayEquals(original,store.sealed);assertEquals(0,store.deletes)
    }
    @Test fun `failed replacement save preserves the old account in memory and encrypted storage`() {
        val store=Store();val s=restored(store);connect(s);val original=store.sealed!!.clone()
        store.failSave=true;connect(s,"FAKE_TOKEN_B")
        assertEquals(AccountSessionPhase.RETRYABLE,s.state.value.phase)
        assertArrayEquals(original,store.sealed)
        s.read(Read(inspect={ assertTrue(it.body.toString(Charsets.UTF_8).contains("FAKE_TOKEN_A")) }),1793000100,654321)
        assertTrue(s.state.value.lastRead!!.readAccepted)
    }
    @Test fun `explicit logout removes ciphertext and memory so another process restores no login`() {
        val store=Store();val s=restored(store);connect(s)
        val sealed=store.sealed!!;s.clear()
        assertTrue(sealed.all { it==0.toByte() });assertNull(store.sealed)
        assertFalse(s.state.value.credentialsPersisted)
        assertEquals(AccountSessionPhase.EMPTY,restored(store).state.value.phase)
    }
    @Test fun `unknown stored fields and unsupported schemas never become reusable auth`() {
        val projected=Json.parseToJsonElement(AccountReadProbe.projectForStorage(request()).toString(Charsets.UTF_8)).jsonObject
        val changed=JsonObject(projected+mapOf("schema" to JsonPrimitive(2)))
        val extra=JsonObject(projected+mapOf("parameters" to JsonObject(projected.getValue("parameters").jsonObject+mapOf("student_num" to JsonPrimitive("PRIVATE")))))
        for(root in listOf(changed,extra)) {
            try { AccountReadProbe.restoreFromStorage(root.toString().toByteArray());fail("Must reject") } catch(_:IllegalArgumentException) {}
        }
    }
    @Test fun `saved account report exports restoration evidence without secrets or motion authorization`() {
        val store=Store();connect(restored(store));val s=restored(store);s.read(Read(),1793000100,654321)
        val text=AccountSessionReport.summary(s.state.value).toString()
        val report=Json.parseToJsonElement(text).jsonObject
        assertTrue(report.getValue("credentials_persisted").jsonPrimitive.boolean)
        assertTrue(report.getValue("restored_from_storage").jsonPrimitive.boolean)
        assertFalse(report.getValue("configuration_owner_verified").jsonPrimitive.boolean)
        assertFalse(report.getValue("automatic_refresh_supported").jsonPrimitive.boolean)
        for(secret in listOf("FAKE_TOKEN","FAKE_DEVICE","DO_NOT_STORE","parameters","user_agent")) assertFalse(text.contains(secret))
    }
}
