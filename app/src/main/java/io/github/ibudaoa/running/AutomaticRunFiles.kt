package io.github.ibudaoa.running

import io.github.ibudaoa.diagnostics.DiagnosticConfig
import io.github.ibudaoa.domain.ConfigurationReviewer
import io.github.ibudaoa.roads.CampusGeometry
import io.github.ibudaoa.roads.ClientRegionEvidence
import io.github.ibudaoa.roads.RouteRegionMode
import kotlinx.serialization.json.*
import java.io.Closeable
import java.util.Base64
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.*

/** Generated execution data. Never constructed as device GPS, real steps or measured sensor events. */
internal class AutomaticRunFiles(val track:ByteArray,val motion:ByteArray,val fields:Map<String,String>):Closeable {
    override fun close() { track.fill(0);motion.fill(0) }
    override fun toString()="AutomaticRunFiles(generated)"
    companion object {
        fun build(context:RunRequestContext,config:DiagnosticConfig,timeline:AutomaticRouteRun,trackName:String):AutomaticRunFiles {
            return build(context,config,timeline.completion(),trackName)
        }
        fun build(context:RunRequestContext,config:DiagnosticConfig,snapshot:AutomaticRunSnapshot,trackName:String):AutomaticRunFiles {
            snapshot.validate(context)
            fun id(k:String)=(config.config[k] as? JsonPrimitive)?.longOrNull
            require(id("school_id")==context.school && id("term_id")==context.term && id("run_zone_id")==context.zone)
            val start=context.registeredStart ?: error("Automatic run not registered")
            require(context.registrationProof!=null && snapshot.elapsedMillis>=1000)
            val trace=snapshot.samples
            val area=ConfigurationReviewer.review(config).allowedBoundary?.let(CampusGeometry::prepare) ?: error("Unknown area")
            val region=ClientRegionEvidence.forCurrentClient(config)
            require(region==RouteRegionMode.CLIENT_FENCE_DISABLED) { "Automatic region branch not supported" }
            val elapsed=snapshot.elapsedSeconds
            val last=trace.last();val visited=snapshot.visits.associateBy { it.id }
            var splitMeters=0.0;var splitMillis=0L;var insideCount=0
            val rows=buildJsonArray {
                trace.forEachIndexed { index,sample ->
                    val inside=region==RouteRegionMode.CLIENT_FENCE_DISABLED || area.segmentInside(sample.coordinate,sample.coordinate);if(inside)insideCount++
                    add(buildJsonObject {
                        put("o",sample.coordinate.longitude.toFloat().toDouble());put("a",sample.coordinate.latitude.toFloat().toDouble())
                        put("s",sample.speed.toFloat().toDouble())
                        if(index==trace.lastIndex)put("p",1)
                        if(!inside)put("l",1)
                        if(sample.meters-splitMeters>=1000 || index==trace.lastIndex) {
                            put("b",((sample.meters-splitMeters)/1000).toFloat().toDouble())
                            val end=if(index==trace.lastIndex)snapshot.elapsedMillis else sample.elapsedMillis
                            put("c",(end-splitMillis)/1000.0);splitMeters=sample.meters;splitMillis=end
                        }
                    })
                }
            }
            val plain=rows.toString().toByteArray()
            val cipher=Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE,SecretKeySpec("Wet2C8d34f62ndi3".toByteArray(),"AES"),IvParameterSpec("K6iv85jBD8jgf32D".toByteArray()))
            val encrypted=try { cipher.doFinal(plain) } finally { plain.fill(0) }
            val packed=try { (Base64.getMimeEncoder(76,byteArrayOf(10)).encodeToString(encrypted)+"\n").toByteArray() } finally { encrypted.fill(0) }
            // Deterministic virtual kinematics, explicitly generated rather than sampled from a phone.
            var oldHeading=0.0;var vx=0.0;var vy=0.0
            fun decimal(value:Double)=String.format(Locale.ROOT,"%.2f",value)
            // Official RunHelper.b0 stores Float coordinates; y9.c uses j0.k (six-place HALF_EVEN).
            val coordinateFormat=java.text.DecimalFormat("0.000000",java.text.DecimalFormatSymbols(Locale.ROOT))
            val motion=buildJsonArray {
                trace.forEachIndexed { i,s ->
                    val old=trace.getOrNull(i-1)
                    val dt=old?.let { (s.elapsedMillis-it.elapsedMillis)/1000.0 } ?: 0.0
                    var ax=0.0;var ay=0.0;var gz=0.0
                    if(old!=null && dt>0) {
                        val nx=(s.coordinate.longitude-old.coordinate.longitude)*111320*cos(Math.toRadians(s.coordinate.latitude))/dt
                        val ny=(s.coordinate.latitude-old.coordinate.latitude)*110574/dt
                        ax=(nx-vx)/dt;ay=(ny-vy)/dt
                        val heading=atan2(ny,nx)
                        var turn=heading-oldHeading;while(turn>PI)turn-=2*PI;while(turn< -PI)turn+=2*PI
                        gz=turn/dt;vx=nx;vy=ny;oldHeading=heading
                    }
                    add(buildJsonObject {
                        put("n",coordinateFormat.format(s.coordinate.longitude.toFloat().toDouble()))
                        put("a",coordinateFormat.format(s.coordinate.latitude.toFloat().toDouble()))
                        put("t",start*1000+s.elapsedMillis)
                        put("ax",decimal(ax));put("ay",decimal(ay));put("az","0.00")
                        put("gx","0.00");put("gy","0.00");put("gz",decimal(gz))
                    })
                }
            }.toString().toByteArray()
            val logData=buildJsonArray {
                context.points.forEach { id ->
                    require(id.toLongOrNull()?.let { it>0 }==true)
                    val sample=visited[id]?.sample
                    add(buildJsonObject {
                        put("point_id",id.toLong());put("time",sample?.let { start+it.elapsedMillis/1000 } ?: 0L)
                        put("latitude",sample?.coordinate?.latitude?.toFloat()?.toDouble() ?: 0.0)
                        put("longtitude",sample?.coordinate?.longitude?.toFloat()?.toDouble() ?: 0.0)
                        put("distance",sample?.meters?.div(1000)?.toFloat()?.toDouble() ?: 0.0)
                    })
                }
            }
            val bins=snapshot.minuteSteps();val steps=bins.sum()
            val fields=mapOf("term_id" to context.term.toString(),"game_id" to context.zone.toString(),"start_time" to start.toString(),
                "end_time" to (start+elapsed).toString(),"used_time" to elapsed.toString(),"distance" to String.format(Locale.ROOT,"%.2f",last.meters/1000),
                "file_img" to "1","gee_token" to "","newMobileDeviceId" to "","record_file" to trackName,"record_img" to "","log_data" to logData.toString(),
                "step_num" to steps.toString(),"step_info" to buildJsonObject { put("interval",60);put("list",JsonArray(bins.map(::JsonPrimitive))) }.toString(),
                "record_str" to RunFinishFields.recordSignature(context),"pass_total" to trace.size.toString(),"pass_effective_num" to insideCount.toString(),
                "is_running_area_valid" to if(insideCount==trace.size)"1" else "0","run_check_res" to "[]","run_face_verify_id" to "1231231231231","is_timeout_zfb" to "0")
            return AutomaticRunFiles(packed,motion,fields)
        }
    }
}
