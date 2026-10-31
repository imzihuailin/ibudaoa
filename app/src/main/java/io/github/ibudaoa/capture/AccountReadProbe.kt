package io.github.ibudaoa.capture

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.TreeMap
import kotlinx.serialization.json.*

/** Only safe outcomes leave the request handler. No reusable session or credential object is created. */
enum class AccountReadOutcome {
    PASSED, READ_ACCEPTED_UNBOUND, UNSUPPORTED_REQUEST, SIGNATURE_MISMATCH, ORIGINAL_ACCOUNT_INVALID,
    ACCOUNT_EXPIRED, ACCOUNT_CHANGED, ACCOUNT_MISMATCH, HTTP_ERROR, NETWORK_ERROR, INVALID_RESPONSE;
    val label:String get()=when(this) {
        PASSED -> "账号只读校验通过"
        READ_ACCEPTED_UNBOUND -> "独立读取成功，响应未提供账号标识，归属未核实"
        UNSUPPORTED_REQUEST -> "官方账号请求暂未适配"
        SIGNATURE_MISMATCH -> "账号请求验证未通过"
        ORIGINAL_ACCOUNT_INVALID -> "官方账号响应未提供可核对的账号标识"
        ACCOUNT_EXPIRED -> "登录信息已失效，请在官方应用确认"
        ACCOUNT_CHANGED -> "账号会话已变化，请在官方应用确认"
        ACCOUNT_MISMATCH -> "账号响应与本次请求不一致"
        HTTP_ERROR -> "账号服务返回未能确认"
        NETWORK_ERROR -> "只读校验网络未完成"
        INVALID_RESPONSE -> "账号服务响应暂未识别"
    }
}
enum class AccountReadReason { REQUEST_TARGET, BODY_BOUNDS, CONTENT_TYPE, INVALID_FORM, DUPLICATE_FIELD,
    ENCRYPTED_REQUEST, MISSING_AUTH, MISSING_SIGNATURE, SIGNATURE_MISMATCH, MISSING_CLOCK, ACCOUNT_MISMATCH,
    RESPONSE_ACCOUNT_MISSING, RESPONSE_ACCOUNT_INVALID }
data class AccountReadResult(val outcome: AccountReadOutcome, val independentRequests: Int = 0,
    val originalSignatureVerified: Boolean = false, val accountMatched: Boolean = false,
    val reason: AccountReadReason? = null) {
    val readAccepted: Boolean get() = outcome in setOf(AccountReadOutcome.PASSED, AccountReadOutcome.READ_ACCEPTED_UNBOUND)
}

/** Exact User/User only. Original identity fields live solely in this call and are never exported. */
internal object AccountReadProbe {
    // Public protocol constant, confirmed against official 4.3.1 ea.k / utils.x0 / o9.b.
    private const val SIGN_SALT="rDJiNB9j7vD2"
    private const val MAX_REQUEST=64*1024
    private class Unsupported(val reason:AccountReadReason=AccountReadReason.INVALID_FORM) : Exception("Unsupported account check request")
    class HttpFailure : Exception("Account check HTTP failure")

    /** At most one retry, exclusively for transport failures, with a fresh signed request. */
    fun checkWithRetry(request: LocalRequest, response: ByteArray, nowSeconds: () -> Long, nonce: () -> Int,
        isCancelled: () -> Boolean = { false }, onAccepted: ((LocalRequest) -> Unit)? = null,
        send: (ByteArray) -> ByteArray): AccountReadResult {
        var result = AccountReadResult(AccountReadOutcome.NETWORK_ERROR)
        var previousNonce: Int? = null
        var sent=0
        val originalNonce = runCatching { parse(request.body)["nonce"]?.toIntOrNull() }.getOrNull()
        try {
            repeat(2) {
                if (isCancelled()) return result.copy(independentRequests=sent)
                var next = nonce()
                while (next == previousNonce || next == originalNonce)
                    next = 100_000 + (next - 100_000 + 1).mod(900_000)
                previousNonce = next
                result = check(request.copy(body=request.body.clone()), response, nowSeconds(), next, onAccepted, send)
                sent+=result.independentRequests
                if (result.outcome != AccountReadOutcome.NETWORK_ERROR) return result.copy(independentRequests=sent)
            }
            return result.copy(independentRequests=sent)
        } finally { request.body.fill(0) }
    }

    fun check(request: LocalRequest, originalResponse: ByteArray, nowSeconds: Long, nonce: Int,
        onAccepted: ((LocalRequest) -> Unit)? = null,
        send: (ByteArray) -> ByteArray): AccountReadResult {
        var outbound:ByteArray?=null
        var response:ByteArray?=null
        var sent=false
        var verified=false
        try {
            if (request.method!="POST" || request.target!=ReadOnlyPolicy.USER_PROFILE) throw Unsupported(AccountReadReason.REQUEST_TARGET)
            if (request.body.size !in 1..MAX_REQUEST) throw Unsupported(AccountReadReason.BODY_BOUNDS)
            val types=request.headers.filter { it.first.equals("Content-Type",true) }
            if (types.size!=1 || types.single().second.substringBefore(';').trim().lowercase()!="application/x-www-form-urlencoded") throw Unsupported(AccountReadReason.CONTENT_TYPE)
            val params=parse(request.body)
            if ("key" in params) throw Unsupported(AccountReadReason.ENCRYPTED_REQUEST)
            val uid=params["uid"]?.takeIf { it.matches(Regex("[1-9][0-9]{0,19}")) } ?: throw Unsupported(AccountReadReason.MISSING_AUTH)
            if (params["token"].isNullOrBlank()) throw Unsupported(AccountReadReason.MISSING_AUTH)
            val previous=params["sign"]?.takeIf { it.matches(Regex("[a-fA-F0-9]{32}")) } ?: throw Unsupported(AccountReadReason.MISSING_SIGNATURE)
            if (params["timestamp"]?.toLongOrNull()?.let { it>0 }!=true || params["nonce"].isNullOrBlank()) throw Unsupported(AccountReadReason.MISSING_CLOCK)
            // Determine the observed signing form locally; no speculative network retry.
            val strip=when {
                signature(params,false).equals(previous,true) -> false
                signature(params,true).equals(previous,true) -> true
                else -> return AccountReadResult(AccountReadOutcome.SIGNATURE_MISMATCH,reason=AccountReadReason.SIGNATURE_MISMATCH)
            }
            verified=true
            val original=try { BeforeRunDecoder.decodeSuccessfulData(originalResponse) }
                catch (e: BeforeRunAccountException) { return AccountReadResult(accountFailure(e),originalSignatureVerified=true) }
                catch (_: Exception) { return AccountReadResult(AccountReadOutcome.ORIGINAL_ACCOUNT_INVALID,originalSignatureVerified=true) }
            val originalIdentityProblem=identityProblem(original,uid)
            if (originalIdentityProblem!=null && originalIdentityProblem!=AccountReadReason.RESPONSE_ACCOUNT_MISSING)
                return AccountReadResult(if(originalIdentityProblem==AccountReadReason.ACCOUNT_MISMATCH)
                    AccountReadOutcome.ACCOUNT_MISMATCH else AccountReadOutcome.ORIGINAL_ACCOUNT_INVALID,
                    originalSignatureVerified=true,reason=originalIdentityProblem)
            if (nowSeconds<=0 || nonce !in 100_000..999_999) throw Unsupported()
            params["timestamp"]=nowSeconds.toString()
            params["nonce"]=if (params["nonce"]==nonce.toString()) (if (nonce==999_999) 100_000 else nonce+1).toString() else nonce.toString()
            params.remove("sign")
            params["sign"]=signature(params,strip)
            outbound=params.entries.joinToString("&") { (k,v) -> encode(k)+"="+encode(v) }.toByteArray(Charsets.UTF_8)
            sent=true
            response=send(outbound)
            val data=try { BeforeRunDecoder.decodeSuccessfulData(response) }
                catch (e: BeforeRunAccountException) { return AccountReadResult(accountFailure(e),1,true) }
                catch (_: Exception) { return AccountReadResult(AccountReadOutcome.INVALID_RESPONSE,1,true) }
            val identityProblem=identityProblem(data,uid)
            if (identityProblem==AccountReadReason.RESPONSE_ACCOUNT_MISSING) {
                onAccepted?.invoke(request.copy(body=outbound))
                return AccountReadResult(AccountReadOutcome.READ_ACCEPTED_UNBOUND,1,true,false,identityProblem)
            }
            if (identityProblem!=null) return AccountReadResult(if(identityProblem==AccountReadReason.ACCOUNT_MISMATCH)
                AccountReadOutcome.ACCOUNT_MISMATCH else AccountReadOutcome.INVALID_RESPONSE,1,true,reason=identityProblem)
            onAccepted?.invoke(request.copy(body=outbound))
            return AccountReadResult(AccountReadOutcome.PASSED,1,true,true)
        } catch (e: Unsupported) {
            return AccountReadResult(AccountReadOutcome.UNSUPPORTED_REQUEST,if(sent)1 else 0,verified,reason=e.reason)
        } catch (_: HttpFailure) {
            return AccountReadResult(AccountReadOutcome.HTTP_ERROR,if(sent)1 else 0,verified)
        } catch (_: Exception) {
            return AccountReadResult(if(sent)AccountReadOutcome.NETWORK_ERROR else AccountReadOutcome.UNSUPPORTED_REQUEST,
                if(sent)1 else 0,verified,reason=if(!sent)AccountReadReason.INVALID_FORM else null)
        } finally {
            outbound?.fill(0);response?.fill(0)
            // The proxy already delivered the official request before entering this method.
            request.body.fill(0)
        }
    }
    /** Reuse only already accepted request material; caller owns its short, foreground-only lifetime. */
    fun readRetained(request:LocalRequest, nowSeconds:Long, nonce:Int, send:(ByteArray)->ByteArray):AccountReadResult {
        var outbound:ByteArray?=null
        var response:ByteArray?=null
        var sent=false
        try {
            require(request.method=="POST" && request.target==ReadOnlyPolicy.USER_PROFILE)
            require(request.body.size in 1..MAX_REQUEST)
            val values=parse(request.body)
            val uid=values.getValue("uid")
            require(uid.matches(Regex("[1-9][0-9]{0,19}")) && !values["token"].isNullOrBlank() && "key" !in values)
            val previous=values.getValue("sign")
            val strip=when {
                signature(values,false).equals(previous,true) -> false
                signature(values,true).equals(previous,true) -> true
                else -> return AccountReadResult(AccountReadOutcome.SIGNATURE_MISMATCH)
            }
            require(nowSeconds>0 && nonce in 100_000..999_999)
            values["timestamp"]=nowSeconds.toString()
            values["nonce"]=if(values["nonce"]==nonce.toString()) (if(nonce==999_999)100_000 else nonce+1).toString() else nonce.toString()
            values["sign"]=signature(values,strip)
            outbound=values.entries.joinToString("&") { encode(it.key)+"="+encode(it.value) }.toByteArray(Charsets.UTF_8)
            sent=true
            response=send(outbound)
            val data=try { BeforeRunDecoder.decodeSuccessfulData(response) }
                catch(e:BeforeRunAccountException) { return AccountReadResult(accountFailure(e),1,true) }
                catch(_:Exception) { return AccountReadResult(AccountReadOutcome.INVALID_RESPONSE,1,true) }
            val problem=identityProblem(data,uid)
            return when(problem) {
                null -> AccountReadResult(AccountReadOutcome.PASSED,1,true,true)
                AccountReadReason.RESPONSE_ACCOUNT_MISSING -> AccountReadResult(AccountReadOutcome.READ_ACCEPTED_UNBOUND,1,true,false,problem)
                AccountReadReason.ACCOUNT_MISMATCH -> AccountReadResult(AccountReadOutcome.ACCOUNT_MISMATCH,1,true,false,problem)
                else -> AccountReadResult(AccountReadOutcome.INVALID_RESPONSE,1,true,false,problem)
            }
        } catch(_:HttpFailure) { return AccountReadResult(AccountReadOutcome.HTTP_ERROR,if(sent)1 else 0,sent) }
        catch(_:Exception) { return AccountReadResult(if(sent)AccountReadOutcome.NETWORK_ERROR else AccountReadOutcome.UNSUPPORTED_REQUEST,if(sent)1 else 0,sent) }
        finally { outbound?.fill(0);response?.fill(0);request.body.fill(0) }
    }
    /** Presence/type failures are protocol evidence, never proof that the account changed. */
    private fun identityProblem(data:JsonObject,expected:String):AccountReadReason? {
        val field=data["uid"] ?: return AccountReadReason.RESPONSE_ACCOUNT_MISSING
        val value=(field as? JsonPrimitive)?.content
            ?.takeIf { it.matches(Regex("[0-9]{1,19}")) }?.toLongOrNull()?.takeIf { it>0 }
            ?: return AccountReadReason.RESPONSE_ACCOUNT_INVALID
        return if(value==expected.toLongOrNull()) null else AccountReadReason.ACCOUNT_MISMATCH
    }
    private fun accountFailure(e:BeforeRunAccountException)=when(e.problem) {
        CaptureProblem.ACCOUNT_EXPIRED -> AccountReadOutcome.ACCOUNT_EXPIRED
        CaptureProblem.ACCOUNT_CHANGED -> AccountReadOutcome.ACCOUNT_CHANGED
        else -> AccountReadOutcome.ORIGINAL_ACCOUNT_INVALID
    }
    private fun parse(bytes:ByteArray):TreeMap<String,String> {
        val text=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
        val pairs=text.split('&')
        if (pairs.size !in 3..80) throw Unsupported()
        val result=TreeMap<String,String>()
        for (pair in pairs) {
            val i=pair.indexOf('=');if (i<=0) throw Unsupported()
            val key=URLDecoder.decode(pair.substring(0,i),"UTF-8")
            val value=URLDecoder.decode(pair.substring(i+1),"UTF-8")
            if (key in result) throw Unsupported(AccountReadReason.DUPLICATE_FIELD)
            if (!key.matches(Regex("[A-Za-z0-9_]{1,64}")) || value.length>4096 ||
                value.any { it.isISOControl() }) throw Unsupported()
            result[key]=value
        }
        return result
    }
    private fun signature(values:Map<String,String>,strip:Boolean):String {
        val joined=buildString { values.toSortedMap().forEach { (key,value) -> if (key!="sign") { append(key);append(value) } };append(SIGN_SALT) }
        val plain=(if(strip)joined.replace(" ","") else joined).toByteArray(Charsets.UTF_8)
        return try { MessageDigest.getInstance("MD5").digest(plain).joinToString("") { "%02x".format(it.toInt() and 255) } }
        finally { plain.fill(0) }
    }
    private fun encode(value:String)=URLEncoder.encode(value,"UTF-8")

    // Official ea.h common auth/device fields. Never persist arbitrary form fields, cookies,
    // student/card identifiers, complete HTTP messages, clocks or old signatures.
    private val storedKeys=setOf("uid","token","school_id","version","version_name","mobileModel",
        "mobileDeviceId","mobileOsVersion","ostype")

    internal fun projectForStorage(request:LocalRequest):ByteArray {
        require(request.method=="POST" && request.target==ReadOnlyPolicy.USER_PROFILE && request.body.size in 1..MAX_REQUEST)
        val values=parse(request.body)
        val previous=values.getValue("sign")
        val strip=when {
            signature(values,false).equals(previous,true) -> false
            signature(values,true).equals(previous,true) -> true
            else -> error("Invalid accepted material")
        }
        val selected=values.filterKeys { it in storedKeys }
        validateStored(selected)
        val agent=request.headers.singleOrNull { it.first.equals("User-Agent",true) }?.second
            ?.takeIf { it.length in 1..512 && it.all { c -> c.code in 32..126 } }
        return buildJsonObject {
            put("schema",1);put("strip_spaces",strip)
            put("parameters",JsonObject(selected.mapValues { JsonPrimitive(it.value) }))
            agent?.let { put("user_agent",it) }
        }.toString().toByteArray(Charsets.UTF_8)
    }

    internal fun restoreFromStorage(bytes:ByteArray):LocalRequest {
        require(bytes.size in 1..MAX_REQUEST)
        val root=Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        require(root.keys.all { it in setOf("schema","strip_spaces","parameters","user_agent","umid","refresh_token","refresh_state") })
        require(root.getValue("schema").jsonPrimitive.int==1)
        val strip=root.getValue("strip_spaces").jsonPrimitive.boolean
        root["umid"]?.let { require(it.jsonPrimitive.isString && it.jsonPrimitive.content.matches(Regex("[!-~]{1,256}"))) }
        root["refresh_token"]?.let { require(it.jsonPrimitive.isString && it.jsonPrimitive.content.length in 1..4096 && it.jsonPrimitive.content.none(Char::isISOControl)) }
        root["refresh_state"]?.let { require(it.jsonPrimitive.content in setOf("AVAILABLE","IN_FLIGHT","UNVERIFIED")) }
        val params=root.getValue("parameters").jsonObject.mapValues {
            it.value.jsonPrimitive.also { p -> require(p.isString) }.content
        }.toMutableMap()
        validateStored(params)
        val headers=mutableListOf("Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8")
        root["user_agent"]?.jsonPrimitive?.let {
            require(it.isString && it.content.length in 1..512 && it.content.all { c -> c.code in 32..126 })
            headers.add("User-Agent" to it.content)
        }
        // Temporary signed seed; readRetained replaces these before any network operation.
        params["timestamp"]="1";params["nonce"]="100000"
        params["sign"]=signature(params,strip)
        return LocalRequest("POST",ReadOnlyPolicy.USER_PROFILE,headers,
            params.toSortedMap().entries.joinToString("&") { encode(it.key)+"="+encode(it.value) }.toByteArray(Charsets.UTF_8))
    }
    private fun validateStored(values:Map<String,String>) {
        require(values.keys.all { it in storedKeys } && values["uid"]?.matches(Regex("[1-9][0-9]{0,19}"))==true && !values["token"].isNullOrBlank())
        require(values.values.all { it.length<=4096 && it.none(Char::isISOControl) })
    }
}
