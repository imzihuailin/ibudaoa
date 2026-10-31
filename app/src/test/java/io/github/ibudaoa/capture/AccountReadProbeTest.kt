package io.github.ibudaoa.capture

import io.github.ibudaoa.diagnostics.PreparationExport
import io.github.ibudaoa.diagnostics.DiagnosticCodec
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AccountReadProbeTest {
    private val values=sortedMapOf("uid" to "42","token" to "FAKE_TOKEN_SENTINEL",
        "timestamp" to "1793000000","nonce" to "123456","mobileModel" to "Dummy Model + Percent%20")
    private fun sign(p:Map<String,String>,strip:Boolean=false):String {
        val text=p.toSortedMap().filterKeys { it!="sign" }.entries.joinToString("") { it.key+it.value }+"rDJiNB9j7vD2"
        return MessageDigest.getInstance("MD5").digest((if(strip)text.replace(" ","") else text).toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun request(p:Map<String,String> = values,strip:Boolean=false,path:String=ReadOnlyPolicy.USER_PROFILE):LocalRequest {
        val signed=p+mapOf("sign" to sign(p,strip))
        return LocalRequest("POST",path,listOf("Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"),
            signed.entries.joinToString("&") { URLEncoder.encode(it.key,"UTF-8")+"="+URLEncoder.encode(it.value,"UTF-8") }.toByteArray())
    }
    private fun response(uid:String="42")="""{"status":1,"data":{"uid":$uid,"name":"测试","school_name":"学校"}}""".toByteArray()
    private fun fields(body:ByteArray)=body.toString(Charsets.UTF_8).split('&').associate {
        val pair=it.split('=',limit=2);URLDecoder.decode(pair[0],"UTF-8") to URLDecoder.decode(pair[1],"UTF-8")
    }

    @Test fun `independent read regenerates timestamp nonce and signature while preserving exact decoded identity fields`() {
        val original=request();val reply=response()
        var sent:ByteArray?=null;var count=0
        val result=AccountReadProbe.check(original,response(),1793000100,654321) { body ->
            count++;sent=body
            val parsed=fields(body)
            assertEquals("1793000100",parsed["timestamp"])
            assertEquals("654321",parsed["nonce"])
            assertEquals(values["mobileModel"],parsed["mobileModel"])
            assertEquals(values["token"],parsed["token"])
            assertEquals("cf6c7e02cbaad2016971feab512cb5fb",parsed["sign"])
            reply
        }
        assertEquals(AccountReadResult(AccountReadOutcome.PASSED,1,true,true),result)
        assertEquals(1,count)
        assertTrue(original.body.all { it==0.toByte() });assertTrue(sent!!.all { it==0.toByte() });assertTrue(reply.all { it==0.toByte() })
    }
    @Test fun `request signature must match locally before any extra network call`() {
        val original=request().let { it.copy(body=it.body.toString(Charsets.UTF_8).replace("FAKE_TOKEN_SENTINEL","TAMPERED").toByteArray()) }
        val result=AccountReadProbe.check(original,response(),1793000100,654321) { error("Must not send") }
        assertEquals(AccountReadOutcome.SIGNATURE_MISMATCH,result.outcome)
        assertEquals(0,result.independentRequests)
        assertTrue(original.body.all { it==0.toByte() })
    }
    @Test fun `account mismatch is rejected both before and after the independent read`() {
        val before=AccountReadProbe.check(request(),response("43"),1793000100,654321) { error("Must not send") }
        assertEquals(AccountReadOutcome.ACCOUNT_MISMATCH,before.outcome);assertEquals(0,before.independentRequests)
        val after=AccountReadProbe.check(request(),response(),1793000100,654321) { response("43") }
        assertEquals(AccountReadOutcome.ACCOUNT_MISMATCH,after.outcome);assertEquals(1,after.independentRequests)
        assertFalse(after.accountMatched)
    }
    @Test fun `successful responses without identity permit one read but cannot establish account match`() {
        var count=0
        val result=AccountReadProbe.check(request(),"""{"status":1,"data":{"name":"测试"}}""".toByteArray(),1793000100,654321) {
            count++;val p=fields(it)
            assertEquals("42",p["uid"]);assertEquals(values["token"],p["token"])
            assertEquals("1793000100",p["timestamp"]);assertEquals("654321",p["nonce"])
            assertEquals(sign(p),p["sign"])
            """{"status":1,"data":{"name":"测试"}}""".toByteArray()
        }
        assertEquals(AccountReadOutcome.READ_ACCEPTED_UNBOUND,result.outcome)
        assertEquals(AccountReadReason.RESPONSE_ACCOUNT_MISSING,result.reason)
        assertTrue(result.originalSignatureVerified)
        assertEquals(1,result.independentRequests);assertEquals(1,count)
        assertTrue(result.readAccepted);assertFalse(result.accountMatched)
    }
    @Test fun `malformed response identity never becomes an account mismatch or a passed read`() {
        for (field in listOf("null","{}","[]","true","0","-42","42.0","9223372036854775808","\"unknown\"")) {
            val original="""{"status":1,"data":{"uid":$field}}""".toByteArray()
            val result=AccountReadProbe.check(request(),original,1793000100,654321) { error("Must not send") }
            assertEquals(AccountReadOutcome.ORIGINAL_ACCOUNT_INVALID,result.outcome)
            assertEquals(AccountReadReason.RESPONSE_ACCOUNT_INVALID,result.reason)
            assertEquals(0,result.independentRequests)
        }
    }
    @Test fun `independent response missing identity cannot establish match even after a successful original match`() {
        val result=AccountReadProbe.check(request(),response(),1793000100,654321) {
            """{"status":1,"data":{}}""".toByteArray()
        }
        assertEquals(AccountReadOutcome.READ_ACCEPTED_UNBOUND,result.outcome)
        assertEquals(AccountReadReason.RESPONSE_ACCOUNT_MISSING,result.reason)
        assertEquals(1,result.independentRequests)
        assertFalse(result.accountMatched)
        val report=AccountReadReport.summary(CaptureState(purpose=CapturePurpose.ACCOUNT_READ_CHECK,accountRead=result))!!.toString()
        assertTrue(report.contains("RESPONSE_ACCOUNT_MISSING"))
        assertFalse(report.contains("FAKE_TOKEN_SENTINEL"))
    }
    @Test fun `missing original identity can be confirmed by matching independent identity`() {
        val result=AccountReadProbe.check(request(),"""{"status":1,"data":{}}""".toByteArray(),1793000100,654321) { response() }
        assertEquals(AccountReadOutcome.PASSED,result.outcome)
        assertTrue(result.accountMatched);assertEquals(1,result.independentRequests)
    }
    @Test fun `missing original identity cannot bypass independent account failure or mismatch`() {
        for ((reply,expected) in listOf(
            """{"status":101,"data":{}}""" to AccountReadOutcome.ACCOUNT_EXPIRED,
            """{"status":102,"data":{}}""" to AccountReadOutcome.ACCOUNT_CHANGED,
            """{"status":1,"data":{"uid":43}}""" to AccountReadOutcome.ACCOUNT_MISMATCH,
            """{"status":1,"data":{"uid":null}}""" to AccountReadOutcome.INVALID_RESPONSE)) {
            var count=0
            val result=AccountReadProbe.check(request(),"""{"status":1,"data":{}}""".toByteArray(),1793000100,654321) {
                count++;reply.toByteArray()
            }
            assertEquals(expected,result.outcome);assertEquals(1,count)
            assertFalse(result.readAccepted);assertFalse(result.accountMatched)
        }
    }
    @Test fun `unbound accepted read report never grants account match or a retained session`() {
        val result=AccountReadResult(AccountReadOutcome.READ_ACCEPTED_UNBOUND,1,true,false,AccountReadReason.RESPONSE_ACCOUNT_MISSING)
        fun summary(r:AccountReadResult)=AccountReadReport.summary(CaptureState(purpose=CapturePurpose.ACCOUNT_READ_CHECK,accountRead=r))
        val report=summary(result)!!
        assertEquals(false,report.getValue("account_matched").jsonPrimitive.boolean)
        assertEquals(false,report.getValue("session_retained").jsonPrimitive.boolean)
        assertNull(summary(result.copy(accountMatched=true)))
        assertNull(summary(result.copy(independentRequests=0)))
        assertNull(summary(result.copy(originalSignatureVerified=false)))
        assertNull(summary(result.copy(reason=null)))
    }
    @Test fun `missing original identity still requires original successful status before sending`() {
        val result=AccountReadProbe.check(request(),"""{"status":0,"data":{}}""".toByteArray(),1793000100,654321) { error("Must not send") }
        assertEquals(AccountReadOutcome.ORIGINAL_ACCOUNT_INVALID,result.outcome)
        assertEquals(0,result.independentRequests);assertFalse(result.readAccepted)
    }
    @Test fun `integer and decimal string identity representations compare by exact long value`() {
        val result=AccountReadProbe.check(request(),response("\"42\""),1793000100,654321) { response("\"00042\"") }
        assertEquals(AccountReadOutcome.PASSED,result.outcome)
        val boundary=values+("uid" to Long.MAX_VALUE.toString())
        val large=AccountReadProbe.check(request(boundary),response(Long.MAX_VALUE.toString()),1793000100,654321) {
            response((Long.MAX_VALUE-1).toString())
        }
        assertEquals(AccountReadOutcome.ACCOUNT_MISMATCH,large.outcome)
    }
    @Test fun `expired response never triggers login refresh or another retry`() {
        var count=0
        val result=AccountReadProbe.check(request(),response(),1793000100,654321) {
            count++;"""{"status":101,"data":{}}""".toByteArray()
        }
        assertEquals(AccountReadOutcome.ACCOUNT_EXPIRED,result.outcome);assertEquals(1,count)
        val sourceExpired=AccountReadProbe.check(request(),"""{"status":102,"data":{}}""".toByteArray(),1793000100,654321) { error("Must not send") }
        assertEquals(AccountReadOutcome.ACCOUNT_CHANGED,sourceExpired.outcome);assertEquals(0,sourceExpired.independentRequests)
    }
    @Test fun `wrong endpoints duplicate fields and unsupported body types are not used for a probe`() {
        for (target in listOf(ReadOnlyPolicy.BEFORE_RUN,"/v3/api.php/Login/RefreshToken",ReadOnlyPolicy.USER_PROFILE+"?x=1")) {
            val result=AccountReadProbe.check(request(path=target),response(),1793000100,654321) { error("Must not send") }
            assertEquals(AccountReadOutcome.UNSUPPORTED_REQUEST,result.outcome)
        }
        val duplicate=request().let { it.copy(body=it.body+"&uid=42".toByteArray()) }
        assertEquals(AccountReadReason.DUPLICATE_FIELD,
            AccountReadProbe.check(duplicate,response(),1793000100,654321) { error("Must not send") }.reason)
        val json=request().copy(headers=listOf("Content-Type" to "application/json"))
        assertEquals(AccountReadReason.CONTENT_TYPE,
            AccountReadProbe.check(json,response(),1793000100,654321) { error("Must not send") }.reason)
    }
    @Test fun `locally verified space stripped signature and nonce collision are handled without retries`() {
        var count=0
        val result=AccountReadProbe.check(request(strip=true),response(),1793000100,123456) { body ->
            count++;val p=fields(body)
            assertEquals("123457",p["nonce"]);assertEquals(sign(p,true),p["sign"])
            response()
        }
        assertEquals(AccountReadOutcome.PASSED,result.outcome);assertEquals(1,count)
    }
    @Test fun `encrypted success response is read with the existing official response decoder`() {
        val cipher=Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE,SecretKeySpec(BeforeRunDecoder.RESPONSE_KEY.toByteArray(),"AES"),
            IvParameterSpec(BeforeRunDecoder.RESPONSE_IV.toByteArray()))
        val encrypted=Base64.getEncoder().encodeToString(cipher.doFinal("""{"uid":42}""".toByteArray()))
        val result=AccountReadProbe.check(request(),response(),1793000100,654321) {
            """{"status":1,"is_encrypt":1,"data":"$encrypted"}""".toByteArray()
        }
        assertEquals(AccountReadOutcome.PASSED,result.outcome)
    }
    @Test fun `network failure produces a safe outcome and wipes the outgoing material`() {
        val original=request();var sent:ByteArray?=null
        val result=AccountReadProbe.check(original,response(),1793000100,654321) {
            sent=it;throw IllegalStateException("PRIVATE_ERROR_SENTINEL")
        }
        assertEquals(AccountReadOutcome.NETWORK_ERROR,result.outcome)
        assertFalse(result.toString().contains("PRIVATE_ERROR_SENTINEL"))
        assertTrue(original.body.all { it==0.toByte() });assertTrue(sent!!.all { it==0.toByte() })
    }
    @Test fun `HTTP failure cannot be reported as a passed account read`() {
        val original=request();var called=0
        val result=AccountReadProbe.check(original,response(),1793000100,654321) {
            called++;throw AccountReadProbe.HttpFailure()
        }
        assertEquals(AccountReadOutcome.HTTP_ERROR,result.outcome)
        assertEquals(1,called);assertEquals(1,result.independentRequests)
        assertFalse(result.accountMatched)
        assertTrue(original.body.all { it==0.toByte() })
    }
    @Test fun `accepted handoff sees only successful refreshed material before it is wiped`() {
        var handoff:ByteArray?=null;var count=0
        val result=AccountReadProbe.check(request(),response(),1793000100,654321,onAccepted={ accepted ->
            count++;handoff=accepted.body
            assertEquals("1793000100",fields(accepted.body)["timestamp"])
        }) { response() }
        assertTrue(result.readAccepted);assertEquals(1,count)
        assertTrue(handoff!!.all { it==0.toByte() })
    }
    @Test fun `rejected server identity never reaches the session handoff`() {
        val result=AccountReadProbe.check(request(),response(),1793000100,654321,onAccepted={ error("Must not hand off") }) { response("43") }
        assertEquals(AccountReadOutcome.ACCOUNT_MISMATCH,result.outcome)
    }

    @Test fun `account report exports only boolean and enum evidence with no profile or secrets`() {
        val result=AccountReadResult(AccountReadOutcome.PASSED,1,true,true)
        val state=CaptureState(phase=CapturePhase.CAPTURED,purpose=CapturePurpose.ACCOUNT_READ_CHECK,accountRead=result,
            message="RAW_MESSAGE_SENTINEL",profile=AccountProfilePreview("PRIVATE_NAME_SENTINEL","PRIVATE_SCHOOL_SENTINEL"))
        val report=ConnectionReport.encode(state,true,true)
        val summary=Json.parseToJsonElement(report).jsonObject.getValue("account_read_check").jsonObject
        assertEquals(false,summary.getValue("session_retained").jsonPrimitive.boolean)
        assertEquals(1,summary.getValue("independent_requests").jsonPrimitive.int)
        for (text in listOf("RAW_MESSAGE_SENTINEL","PRIVATE_NAME_SENTINEL","PRIVATE_SCHOOL_SENTINEL","FAKE_TOKEN_SENTINEL")) assertFalse(report.contains(text))
        val cfg=DiagnosticCodec.decode("""{"school_id":1,"term_id":2,"run_zone_id":3}""")
        val exported=PreparationExport.encode(cfg,1,1,null,accountCheck=state)
        assertTrue(exported.contains("account_read_check"))
        val imported=DiagnosticCodec.decode(exported)
        assertFalse(DiagnosticCodec.encode(imported).contains("account_read_check"))
        assertNull(AccountReadReport.summary(state.copy(purpose=CapturePurpose.CONFIG)))
    }
    @Test fun `transport failure retries once with distinct fresh signed nonce`() {
        var calls=0; val nonces=mutableListOf<String>(); var accepted=0
        val original=request()
        val result=AccountReadProbe.checkWithRetry(original,response(),{1793000100},{123456},onAccepted={accepted++}) { body ->
            val values=fields(body);nonces.add(values.getValue("nonce"))
            assertEquals(sign(values),values["sign"])
            if(calls++==0) throw java.io.IOException("fake timeout")
            response()
        }
        assertTrue(result.readAccepted);assertEquals(2,calls);assertEquals(1,accepted)
        assertEquals(2,nonces.distinct().size);assertFalse(nonces.contains("123456"))
    }
    @Test fun `HTTP rejection identity failure and cancellation do not retry`() {
        var calls=0
        val rejected=AccountReadProbe.checkWithRetry(request(),response(),{1793000100},{654321}) {
            calls++;throw AccountReadProbe.HttpFailure()
        }
        assertEquals(AccountReadOutcome.HTTP_ERROR,rejected.outcome);assertEquals(1,calls)
        calls=0
        AccountReadProbe.checkWithRetry(request(),response(),{1793000100},{654321},isCancelled={true}) {calls++;response()}
        assertEquals(0,calls)
        AccountReadProbe.checkWithRetry(request(),response(),{1793000100},{654321}) {calls++;response("43")}
        assertEquals(1,calls)
    }
    @Test fun `persistent transport failure is bounded to two independent reads`() {
        var calls=0
        val result=AccountReadProbe.checkWithRetry(request(),response(),{1793000100},{654321}) {calls++;throw java.io.IOException()}
        assertEquals(AccountReadOutcome.NETWORK_ERROR,result.outcome);assertEquals(2,calls)
    }

}
