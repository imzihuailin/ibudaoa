package io.github.ibudaoa.running

import io.github.ibudaoa.diagnostics.DiagnosticConfig
import io.github.ibudaoa.domain.ConfigurationReviewer
import io.github.ibudaoa.roads.CampusGeometry
import kotlinx.serialization.json.*
import java.io.Closeable
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Actual measured samples only. No planned route is accepted at this boundary. */
internal class RunTraceArchive private constructor(internal val context:RunRequestContext,private val payload:ByteArray,
    val distanceMeters:Double,val elapsedSeconds:Long,val allInsideArea:Boolean,val pointCount:Int,
    val insidePointCount:Int,private val witnesses:Map<Long,MeasuredRunFix>) : Closeable {
    val sha256:String=hex(MessageDigest.getInstance("SHA-256").digest(payload))
    private var closed=false
    @Synchronized fun uploadBytes():ByteArray { check(!closed);return payload.clone() }
    @Synchronized override fun close() { payload.fill(0);closed=true }
    fun contains(observation:ObservedRunCheckpoint):Boolean {
        val witness=witnesses[observation.fix.sdkTimeMillis] ?: return false
        return witness.fix.coordinate==observation.fix.coordinate &&
            kotlin.math.abs(witness.cumulativeMeters-observation.cumulativeMeters)<0.001
    }
    override fun toString()="RunTraceArchive(private)"
    companion object {
        private fun hex(bytes:ByteArray)=bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        fun build(context:RunRequestContext,config:DiagnosticConfig,progress:RunProgressSummary,trace:List<MeasuredRunFix>):RunTraceArchive {
            require(context.registeredStart?.let { it>0 }==true && context.registrationProof?.matches(Regex("[a-fA-F0-9]{32}"))==true)
            fun id(name:String)=(config.config[name] as? JsonPrimitive)?.longOrNull
            require(id("school_id")==context.school && id("term_id")==context.term && id("run_zone_id")==context.zone)
            val review=ConfigurationReviewer.review(config)
            require(review.forbiddenRegionIsEmpty==true && context.points.size==progress.requiredPoints &&
                review.candidates?.map { it.id }?.toSet()?.containsAll(context.points)==true)
            val area=review.allowedBoundary?.let(CampusGeometry::prepare) ?: error("Run area not verified")
            require(trace.size in 1..20_000 && trace.first().startsSegment && progress.elapsedSeconds>0)
            require(progress.distance.meters.isFinite() && progress.distance.meters>=0 &&
                kotlin.math.abs(trace.last().cumulativeMeters-progress.distance.meters)<0.001 && progress.elapsedSeconds>=trace.last().elapsedSeconds)
            var previous:MeasuredRunFix?=null
            var splitMeters=0.0;var splitSeconds=0L;var allInside=true;var insideCount=0
            val rows=buildJsonArray {
                trace.forEachIndexed { index,sample ->
                    val fix=sample.fix;val p=fix.coordinate;val old=previous
                    require(p.latitude.isFinite() && p.longitude.isFinite() && p.latitude in -90.0..90.0 && p.longitude in -180.0..180.0)
                    require(fix.sdkTimeMillis?.let { it>0 }==true && fix.receivedElapsedMillis>=0 &&
                        fix.accuracyMeters.isFinite() && fix.accuracyMeters>0 && fix.accuracyMeters<=50)
                    require(sample.elapsedSeconds>=0 && sample.cumulativeMeters.isFinite() && sample.cumulativeMeters>=0)
                    if(old==null) require(sample.cumulativeMeters==0.0)
                    else {
                        require(fix.sdkTimeMillis!!>old.fix.sdkTimeMillis!! && fix.receivedElapsedMillis>=old.fix.receivedElapsedMillis &&
                            sample.elapsedSeconds>=old.elapsedSeconds && sample.cumulativeMeters>=old.cumulativeMeters)
                        if(sample.startsSegment) require(kotlin.math.abs(sample.cumulativeMeters-old.cumulativeMeters)<0.001)
                    }
                    val inside=area.segmentInside(p,p);allInside=allInside && inside;if(inside)insideCount++
                    val split=sample.cumulativeMeters-splitMeters>=1000 || index==trace.lastIndex
                    add(buildJsonObject {
                        put("o",p.longitude.toFloat().toDouble());put("a",p.latitude.toFloat().toDouble())
                        if(index==trace.lastIndex || trace[index+1].startsSegment) put("p",1)
                        fix.speedMetersPerSecond?.let { speed ->
                            require(speed.isFinite() && speed>=0 && speed<=100);put("s",speed.toDouble())
                        }
                        if(split) {
                            put("b",((sample.cumulativeMeters-splitMeters)/1000).toFloat().toDouble())
                            val endSeconds=if(index==trace.lastIndex)progress.elapsedSeconds else sample.elapsedSeconds
                            put("c",endSeconds-splitSeconds)
                            splitMeters=sample.cumulativeMeters;splitSeconds=endSeconds
                        }
                        put("l",if(inside)0 else 1)
                    })
                    previous=sample
                }
            }
            val plain=rows.toString().toByteArray()
            val encrypted=try {
                val cipher=Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.ENCRYPT_MODE,SecretKeySpec("Wet2C8d34f62ndi3".toByteArray(),"AES"),IvParameterSpec("K6iv85jBD8jgf32D".toByteArray()))
                cipher.doFinal(plain)
            } finally { plain.fill(0) }
            val packed=try { (Base64.getMimeEncoder(76,byteArrayOf(10)).encodeToString(encrypted)+"\n").toByteArray() }
                finally { encrypted.fill(0) }
            require(packed.size<=8*1024*1024)
            return RunTraceArchive(context,packed,progress.distance.meters,progress.elapsedSeconds,allInside,trace.size,insideCount,trace.associateBy { it.fix.sdkTimeMillis!! })
        }
    }
}
