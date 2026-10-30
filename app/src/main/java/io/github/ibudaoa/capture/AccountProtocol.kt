package io.github.ibudaoa.capture

import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.*

enum class ProtocolCaptureProblem { NONE, REQUEST_SIGNATURE_OR_FORMAT, DEVICE_BINDING, RESPONSE, ACCOUNT_BINDING, STORAGE }
internal class ProtocolCaptureFailure(val problem:ProtocolCaptureProblem):Exception("Account protocol capture not confirmed")

/** Reimplemented from official 4.3.1 ea.k.m/f, utils.a.g and splash.k.u0. No fabricated device defaults. */
internal object AccountProtocol {
    const val REFRESH="/v3/api.php/Login/RefreshToken"
    private const val SALT="eFoqdHOdDy6ViJ0i"
    private const val KEY="goMEzJ1pDpncNHb9"
    private const val IV="ZUrAS2lUidjyY2gK"
    private val common=setOf("uid","token","school_id","version","version_name","mobileModel","mobileDeviceId","mobileOsVersion","ostype")
    private fun md5(text:String)=MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    internal fun sign(values:Map<String,String>)=md5(SALT+values.toSortedMap().entries
        .filter { it.key!="sign" && it.key!="mobileDeviceId" }.joinToString("") { it.key+it.value })
    private fun crypt(bytes:ByteArray,key:String=KEY,iv:String=IV,encrypt:Boolean=true):ByteArray =
        Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(if(encrypt)Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE,SecretKeySpec(key.toByteArray(),"AES"),IvParameterSpec(iv.toByteArray()))
            doFinal(bytes)
        }
    private fun b64(bytes:ByteArray)=Base64.getMimeEncoder(76,byteArrayOf(10)).encodeToString(bytes)+"\n"
    private fun root(bytes:ByteArray):JsonObject {
        require(bytes.size in 1..65536)
        return Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
    }
    private fun params(root:JsonObject)=root.getValue("parameters").jsonObject.mapValues { it.value.jsonPrimitive.content }
    fun ready(bytes:ByteArray):Boolean=runCatching {
        val r=root(bytes);val p=params(r)
        common.all { !p[it].isNullOrBlank() } && p.getValue("mobileModel").length>=2 &&
            r["umid"]?.jsonPrimitive?.content?.matches(Regex("[!-~]{1,256}"))==true
    }.getOrDefault(false)
    fun hasRefresh(bytes:ByteArray)=runCatching { !root(bytes)["refresh_token"]?.jsonPrimitive?.content.isNullOrBlank() }.getOrDefault(false)
    fun refreshState(bytes:ByteArray)=root(bytes)["refresh_state"]?.jsonPrimitive?.content ?: "AVAILABLE"
    fun state(bytes:ByteArray,state:String)=JsonObject(root(bytes)+mapOf("refresh_state" to JsonPrimitive(state))).toString().toByteArray()
    fun request(bytes:ByteArray,path:String,now:Long,nonce:Int):LocalRequest {
        require(path in setOf(ReadOnlyPolicy.BEFORE_RUN,REFRESH) && ready(bytes) && now in 1000..Int.MAX_VALUE && nonce in 100_000..999_999)
        val r=root(bytes);val p=params(r).toMutableMap();p["timestamp"]=now.toString();p["nonce"]=nonce.toString()
        val pair=lpiPairs[(now%15).toInt()]
        val text=(r.getValue("umid").jsonPrimitive.content+md5(now.toString())).toByteArray()
        val lpi=try { b64(crypt(text,pair.first,pair.second)) } finally { text.fill(0) }
        p["lp"+p.getValue("mobileModel")[1].lowercaseChar()+now.toString()[3]]=lpi
        if(path==REFRESH) {
            require(refreshState(bytes)=="AVAILABLE" || refreshState(bytes)=="IN_FLIGHT")
            p["access_token"]=p.getValue("token");p["refresh_token"]=r.getValue("refresh_token").jsonPrimitive.content
        }
        p["sign"]=sign(p)
        val plain=JsonObject(p.mapValues { JsonPrimitive(it.value) }).toString().toByteArray()
        val encrypted=try { crypt(plain) } finally { plain.fill(0) }
        val body=try { ("key="+URLEncoder.encode(b64(encrypted),"UTF-8")).toByteArray() } finally { encrypted.fill(0) }
        val headers=mutableListOf("Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8")
        r["user_agent"]?.jsonPrimitive?.content?.let { headers.add("User-Agent" to it) }
        return LocalRequest("POST",path,headers,body)
    }
    internal fun runParameters(bytes:ByteArray):Map<String,String> {
        require(ready(bytes))
        return params(root(bytes)).filterKeys { it in common }
    }
    internal fun runUserAgent(bytes:ByteArray)=root(bytes)["user_agent"]?.jsonPrimitive?.content
    internal fun runBinding(bytes:ByteArray,now:Long):Pair<String,String> {
        require(ready(bytes) && now in 1000..Int.MAX_VALUE)
        val r=root(bytes);val p=params(r);val pair=lpiPairs[(now%15).toInt()]
        val text=(r.getValue("umid").jsonPrimitive.content+md5(now.toString())).toByteArray()
        val proof=try { b64(crypt(text,pair.first,pair.second)) } finally { text.fill(0) }
        return ("lp"+p.getValue("mobileModel")[1].lowercaseChar()+now.toString()[3]) to proof
    }
    internal fun runEnvelope(values:Map<String,String>):ByteArray {
        val plain=JsonObject(values.mapValues { JsonPrimitive(it.value) }).toString().toByteArray()
        val encrypted=try { crypt(plain) } finally { plain.fill(0) }
        return try { ("key="+URLEncoder.encode(b64(encrypted),"UTF-8")).toByteArray() } finally { encrypted.fill(0) }
    }

    /** Public-protocol decode, bounded and signature checked before any sensitive field is used. */
    internal fun decodeRequest(request:LocalRequest):Map<String,String> {
        require(request.method=="POST" && request.target in setOf(ReadOnlyPolicy.BEFORE_RUN,REFRESH) && request.body.size in 1..65536)
        val types=request.headers.filter { it.first.equals("Content-Type",true) }
        require(types.size==1 && types[0].second.substringBefore(';').trim().equals("application/x-www-form-urlencoded",true))
        val form=request.body.toString(Charsets.UTF_8)
        require(form.startsWith("key=") && '&' !in form)
        val encoded=URLDecoder.decode(form.substring(4),"UTF-8")
        val clear=crypt(Base64.getMimeDecoder().decode(encoded),encrypt=false)
        val p=try { root(clear).mapValues { (_,v) -> v.jsonPrimitive.also { require(it.isString) }.content } }
            finally { clear.fill(0) }
        require(p.size in 5..80 && p.keys.all { it.matches(Regex("[A-Za-z0-9_]{1,64}")) } && p.values.all { it.length<=4096 })
        require(sign(p).equals(p["sign"],true))
        return p
    }
    private fun umid(p:Map<String,String>):String {
        val ts=p.getValue("timestamp");val seconds=ts.toInt();require(seconds>0 && ts.length>=4)
        val model=p.getValue("mobileModel");require(model.length>=2)
        val name="lp"+model[1].lowercaseChar()+ts[3]
        val pair=lpiPairs[seconds%15]
        val bytes=crypt(Base64.getMimeDecoder().decode(p.getValue(name)),pair.first,pair.second,false)
        val decoded=try { bytes.toString(Charsets.UTF_8) } finally { bytes.fill(0) }
        require(decoded.endsWith(md5(ts)))
        return decoded.dropLast(32).also { require(it.matches(Regex("[!-~]{1,256}"))) }
    }
    /** Transient successful official transaction; caller wipes bytes after binding to current auth. */
    fun capture(request:LocalRequest,response:ByteArray):ByteArray {
        val p=checked(ProtocolCaptureProblem.REQUEST_SIGNATURE_OR_FORMAT) { decodeRequest(request) }
        val device=checked(ProtocolCaptureProblem.DEVICE_BINDING) { umid(p) }
        return checked(ProtocolCaptureProblem.RESPONSE) {
        val data=BeforeRunDecoder.decodeSuccessfulData(response)
        val id=p.getValue("uid");require(id.toLongOrNull()?.let { it>0 }==true)
        data["uid"]?.let { require((it as? JsonPrimitive)?.content?.toLongOrNull()==id.toLongOrNull()) }
        val selected=p.filterKeys { it in common }.toMutableMap()
        var refresh:String?=null
        if(request.target==REFRESH) {
            require(p["access_token"]==p["token"] && !p["refresh_token"].isNullOrBlank())
            selected["token"]=secret(data,"access_token")
            refresh=secret(data,"refresh_token")
            require(selected["token"]!=p["token"] && refresh!=p["refresh_token"])
        }
        buildJsonObject {
            put("schema",1);put("strip_spaces",false);put("parameters",JsonObject(selected.mapValues { JsonPrimitive(it.value) }))
            put("umid",device);put("origin_token",p.getValue("token"))
            refresh?.let { put("refresh_token",it);put("refresh_state","AVAILABLE") }
        }.toString().toByteArray()
    }
    }

    fun merge(saved:ByteArray,capture:ByteArray):ByteArray? {
        val a=root(saved);val b=root(capture);val pa=params(a);val pb=params(b)
        if(pa["uid"]!=pb["uid"] || pa["mobileDeviceId"]!=pb["mobileDeviceId"]) return null
        val token=pa["token"];val origin=b.getValue("origin_token").jsonPrimitive.content
        if(token!=origin && token!=pb["token"]) return null
        val combined=a.toMutableMap();combined["umid"]=b.getValue("umid")
        if(token==origin && pb["token"]!=origin) combined["parameters"]=b.getValue("parameters")
        b["refresh_token"]?.let { combined["refresh_token"]=it;combined["refresh_state"]=JsonPrimitive("AVAILABLE") }
        return JsonObject(combined).toString().toByteArray().also {
            val request=AccountReadProbe.restoreFromStorage(it);request.body.fill(0)
        }
    }
    fun inherit(saved:ByteArray,projected:ByteArray):ByteArray {
        val a=root(saved);val b=root(projected);val pa=params(a);val pb=params(b)
        if(listOf("uid","token","mobileDeviceId").any { pa[it]!=pb[it] }) return projected.clone()
        return JsonObject(b+a.filterKeys { it in setOf("umid","refresh_token","refresh_state") }).toString().toByteArray()
    }
    fun rotated(saved:ByteArray,response:ByteArray):ByteArray {
        val r=root(saved);val p=params(r);val data=BeforeRunDecoder.decodeSuccessfulData(response)
        data["uid"]?.let { require(it.jsonPrimitive.content.toLongOrNull()==p.getValue("uid").toLongOrNull()) }
        val token=secret(data,"access_token");val refresh=secret(data,"refresh_token")
        require(token!=p["token"] && refresh!=r["refresh_token"]?.jsonPrimitive?.content)
        return JsonObject(r+mapOf("parameters" to JsonObject((p+mapOf("token" to token)).mapValues { JsonPrimitive(it.value) }),
            "refresh_token" to JsonPrimitive(refresh),"refresh_state" to JsonPrimitive("UNVERIFIED"))).toString().toByteArray()
    }
    private fun <T> checked(problem:ProtocolCaptureProblem,block:()->T):T=try { block() }
        catch(_:Exception) { throw ProtocolCaptureFailure(problem) }
    private fun secret(data:JsonObject,key:String)=data.getValue(key).jsonPrimitive.also {
        require(it.isString && it.content.length in 1..4096 && it.content.none(Char::isISOControl))
    }.content
    // Public protocol constants from official o9.c.f106c9 (ea.o.q=2), never account material.
    private val lpiPairs=listOf(
        "L3xP7kQ2fD9sH5jR" to "N8wB4mG6vK2rT9cE",
        "Z5tF8gS3dA7hJ2pQ" to "Y6rU3eW9qX4sC8vB",
        "Q2mK7jP3nL5iO9uY" to "W4sE8qR2tT5yU3iO",
        "D9fG3hJ7kL2pM5rN" to "B6vC8nX3mZ5dF7gH",
        "P7bN2vF9cD4mK6jA" to "S3hG5jJ8dL7fT2wQ",
        "H5jG8fD3sA6dF9gH" to "K7lJ2pO5iU8yT3rE",
        "X8rV3tB6yG9hN4kP" to "E2sW5qA3dZ7fC8jM",
        "M3dS5fG8hJ2kL7pR" to "C4tY9rU6eW3qX5sZ",
        "F6dG2hJ5kL8pM3rN" to "Z7xC5vB3nA9sD2fH",
        "V5bC3nM8vX4cZ6dF" to "J9kL2jH5gD3fS7dA",
        "B8vN5cM2xZ9dF3gJ" to "H4kL7jP2mQ5rT8sW",
        "N3mK6lJ8pO5iU2yT" to "G5hF7dS9fA3gH4jK",
        "T6rE2yU5iO8pJ3lK" to "J7kH3gF5dS8aQ2wE",
        "W9qE3sA6dF5gH7jK" to "R5tY2eU7iO8pJ3lK",
        "S4dF8aQ3wE6rT5yU" to "I2uO5pJ8lK3jH7gF"
    )
}
