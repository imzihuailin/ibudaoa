package io.github.ibudaoa.capture

import io.github.ibudaoa.running.RunRequestContext
import java.net.URLEncoder
import java.security.MessageDigest

/** Dedicated active-task protocol. The passive configuration VPN retains its original read-only policy. */
internal enum class RunEndpoint(val path:String,val binding:Boolean=false,val encrypted:Boolean=false) {
    ZONE("/v3/api.php/Run/setRunZone"),
    REGISTER("/v3/api.php/Run/getTimestampV278"),
    LOCATION("/v3/api.php/Run/setRunLocationRecord",true),
    STORAGE("/v3/api.php/report/getOssSts",true,true),
    FINISH("/v3/api.php/Run/stopRunV278",true,true),
    RECORD("/v3/api.php/Run/recordDetailV270"),
    GYROSCOPE("/v3/api.php/Run2/gyroscope",true)
}
internal object RunProtocol {
    private val fields=mapOf(
        RunEndpoint.GYROSCOPE to setOf("record_id","gyroscope_file"),
        RunEndpoint.ZONE to setOf("run_zone_id"),RunEndpoint.REGISTER to setOf("game_id","point_ids"),
        RunEndpoint.LOCATION to setOf("game_id","point_ids","latitude","longitude"),RunEndpoint.STORAGE to emptySet(),
        RunEndpoint.RECORD to setOf("record_id","record_uid","year_num"),
        RunEndpoint.FINISH to setOf("term_id","game_id","log_data","file_img","used_time","start_time","end_time","distance",
            "step_num","record_file","record_img","step_info","record_str","pass_total","pass_effective_num","is_running_area_valid",
            "run_check_res","run_face_verify_id","is_timeout_zfb","newMobileDeviceId","gee_token","error_code","mac","ip","oaid")
    )
    private val required=fields+mapOf(RunEndpoint.RECORD to setOf("record_id"),RunEndpoint.FINISH to setOf(
        "term_id","game_id","log_data","file_img","used_time","start_time","end_time","distance","step_num","record_file",
        "step_info","record_str","pass_total","pass_effective_num","is_running_area_valid","run_check_res","run_face_verify_id","is_timeout_zfb"))
    fun request(material:ByteArray,endpoint:RunEndpoint,context:RunRequestContext,extra:Map<String,String>,now:Long,nonce:Int):LocalRequest {
        require(now in 1000..Int.MAX_VALUE && nonce in 100_000..999_999)
        require(context.account>0 && context.school>0 && context.term>0 && context.zone>0 && context.points.size in 2..24 &&
            context.points.distinct().size==context.points.size && context.points.all { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) })
        require(extra.keys.all { it in fields.getValue(endpoint) } && extra.keys.containsAll(required.getValue(endpoint)))
        require(extra.values.all { it.length<=65_536 } && extra.values.sumOf { it.length.toLong() }<=100_000)
        val p=AccountProtocol.runParameters(material).toMutableMap()
        require(p["uid"]?.toLongOrNull()==context.account && p["school_id"]?.toLongOrNull()==context.school)
        extra["game_id"]?.let { require(it==context.zone.toString()) }
        extra["run_zone_id"]?.let { require(it==context.zone.toString()) }
        extra["term_id"]?.let { require(it==context.term.toString()) }
        extra["point_ids"]?.let { require(it==context.points.joinToString(",")) }
        extra["record_uid"]?.let { require(it==context.account.toString()) }
        if(endpoint==RunEndpoint.RECORD) {
            require(extra.getValue("record_id").toLongOrNull()?.let { it>0 }==true)
            extra["year_num"]?.let { require(it.toIntOrNull() in 2000..2100) }
        }
        if(endpoint==RunEndpoint.LOCATION) {
            require(extra.getValue("latitude").toDoubleOrNull()?.let { it.isFinite() && it in -90.0..90.0 }==true)
            require(extra.getValue("longitude").toDoubleOrNull()?.let { it.isFinite() && it in -180.0..180.0 }==true)
        }
        if(endpoint==RunEndpoint.GYROSCOPE) {
            require(extra.getValue("record_id").toLongOrNull()?.let { it>0 }==true)
            require(extra.getValue("gyroscope_file").matches(io.github.ibudaoa.running.RunStorageGrant.OBJECT_NAME) &&
                extra.getValue("gyroscope_file").startsWith("Public/Upload/file/run_gyroscope/"))
        }
        if(endpoint==RunEndpoint.FINISH) {
            require(context.registeredStart!=null && extra["start_time"]==context.registeredStart.toString())
            require(context.registrationProof?.matches(Regex("[a-fA-F0-9]{32}"))==true)
        }
        p.putAll(extra);p["timestamp"]=now.toString();p["nonce"]=nonce.toString()
        if(endpoint.binding) {
            val binding=AccountProtocol.runBinding(material,now)
            p[binding.first]=if(endpoint==RunEndpoint.FINISH)context.registrationProof!! else binding.second
        }
        p["sign"]=if(endpoint.binding)AccountProtocol.sign(p) else legacySign(p)
        val body=if(endpoint.encrypted)AccountProtocol.runEnvelope(p) else p.toSortedMap().entries.joinToString("&") {
            URLEncoder.encode(it.key,"UTF-8")+"="+URLEncoder.encode(it.value,"UTF-8")
        }.toByteArray()
        require(body.size<=131_072)
        val headers=mutableListOf("Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8")
        AccountProtocol.runUserAgent(material)?.let { agent ->
            require(agent.length<=1024 && agent.none { it.isISOControl() });headers.add("User-Agent" to agent)
        }
        return LocalRequest("POST",endpoint.path,headers,body)
    }
    internal fun legacySign(p:Map<String,String>):String {
        val text=p.toSortedMap().filterKeys { it!="sign" }.entries.joinToString("") { it.key+it.value }+"rDJiNB9j7vD2"
        return MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
