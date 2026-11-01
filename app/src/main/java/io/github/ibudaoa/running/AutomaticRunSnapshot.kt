package io.github.ibudaoa.running

import io.github.ibudaoa.diagnostics.DiagnosticConfig
import io.github.ibudaoa.domain.ConfigurationReviewer
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.ZoneId
import kotlin.math.floor

enum class RunCompletionProblem(val label:String) {
    DISTANCE("本次距离不满足当前规则"), PACE("含暂停的总平均配速不满足当前规则"),
    CHECKPOINTS("本次必需打卡尚未全部经过"), CHECKPOINT_REPORTS("部分经过点位尚无对应位置上报响应"),
    TIME("结束时刻不在本次允许时段内"), RULES("本次规则无法完整核对")
}
internal enum class RunMaterialProblem { IDENTITY, TIME, SAMPLES, DISTANCE, CHECKPOINTS, STEPS }
internal class RunMaterialException(val problem:RunMaterialProblem):IllegalStateException(problem.name)

/** Memory-only generated data, frozen before storage authorization or any file upload. */
internal class AutomaticRunSnapshot(samples:List<AutomaticRunSample>,visits:List<AutomaticCheckpoint>,ids:Set<String>,
    val elapsedMillis:Long,val activeMillis:Long,private val owner:RunRequestContext?=null) {
    val samples:List<AutomaticRunSample> = java.util.Collections.unmodifiableList(samples.toList())
    val visits:List<AutomaticCheckpoint> = java.util.Collections.unmodifiableList(visits.toList())
    val ids:Set<String> = java.util.Collections.unmodifiableSet(ids.toSet())
    val elapsedSeconds get()=elapsedMillis/1000
    val meters get()=samples.last().meters
    fun validate(context:RunRequestContext) {
        fun ensure(ok:Boolean,problem:RunMaterialProblem) { if(!ok)throw RunMaterialException(problem) }
        ensure(owner!=null && owner.account==context.account && owner.school==context.school && owner.term==context.term &&
            owner.zone==context.zone && owner.points==context.points && owner.registeredStart==context.registeredStart &&
            owner.registrationProof==context.registrationProof,RunMaterialProblem.IDENTITY)
        ensure(context.account in 1..Int.MAX_VALUE && context.school>0 && context.term>0 && context.zone>0 &&
            context.registeredStart?.let { it>0 }==true && context.registrationProof?.matches(Regex("[a-fA-F0-9]{32}"))==true &&
            context.points.size==ids.size && context.points.toSet()==ids && ids.size in 2..8 &&
            ids.all { it.toLongOrNull()?.let { n -> n>0 }==true },RunMaterialProblem.IDENTITY)
        ensure(elapsedMillis in 1000..86_400_000 && activeMillis in 1..elapsedMillis,RunMaterialProblem.TIME)
        ensure(samples.size in 2..20_000,RunMaterialProblem.SAMPLES)
        ensure(samples.first().elapsedMillis==0L && samples.first().activeMillis==0L && samples.first().meters==0.0 &&
            samples.last().elapsedMillis==elapsedMillis && samples.last().activeMillis==activeMillis,RunMaterialProblem.TIME)
        ensure(samples.all { s -> s.elapsedMillis in 0..elapsedMillis && s.activeMillis in 0..s.elapsedMillis &&
            s.speed.isFinite() && s.speed>=0 && s.coordinate.latitude.isFinite() && s.coordinate.longitude.isFinite() &&
            s.coordinate.latitude in -90.0..90.0 && s.coordinate.longitude in -180.0..180.0 },RunMaterialProblem.SAMPLES)
        ensure(samples.all { it.meters.isFinite() && it.meters>=0 } && meters>0,RunMaterialProblem.DISTANCE)
        ensure(samples.zipWithNext().all { (a,b) -> b.elapsedMillis>a.elapsedMillis && b.activeMillis>=a.activeMillis &&
            b.meters>=a.meters && (b.activeMillis!=a.activeMillis || b.meters==a.meters) },RunMaterialProblem.TIME)
        ensure(visits.map { it.id }.distinct().size==visits.size && visits.all { it.id in ids && it.sample in samples },RunMaterialProblem.CHECKPOINTS)
        val bins=minuteSteps()
        ensure(bins.size in 1..1441 && bins.all { it in 0..1000 } && bins.sumOf { it.toLong() }==floor(meters/0.8).toLong() &&
            bins.sumOf { it.toLong() } in 0..100_000,RunMaterialProblem.STEPS)
        try { Math.addExact(Math.multiplyExact(context.registeredStart!!,1000),elapsedMillis) }
        catch(_:ArithmeticException) { throw RunMaterialException(RunMaterialProblem.TIME) }
    }
    fun minuteSteps():List<Int> {
        val bins=MutableList((elapsedMillis/60_000+1).toInt()) { 0 }
        var steps=0
        samples.forEach { s ->
            val count=floor(s.meters/0.8).toInt()
            bins[(s.elapsedMillis/60_000).toInt()]+=count-steps;steps=count
        }
        return bins.toList()
    }
    fun ruleProblems(config:DiagnosticConfig,start:Long):Set<RunCompletionProblem> {
        val zone=ZoneId.of("Asia/Shanghai")
        val begin=Instant.ofEpochSecond(start).atZone(zone)
        val end=begin.plusSeconds(elapsedSeconds)
        val rule=ConfigurationReviewer.review(config).uniqueTimeRule(begin.toLocalTime().toSecondOfDay())
            ?: return setOf(RunCompletionProblem.RULES)
        val reportedKm=String.format(java.util.Locale.ROOT,"%.2f",meters/1000).toDouble()
        return buildSet {
            if(meters<rule.minimumKm*1000 || meters>rule.maximumKm*1000 || reportedKm<rule.minimumKm || reportedKm>rule.maximumKm)add(RunCompletionProblem.DISTANCE)
            val pace=elapsedSeconds*1000/ meters
            if(pace<rule.fastestSecondsPerKm || pace>rule.slowestSecondsPerKm)add(RunCompletionProblem.PACE)
            if(visits.map { it.id }.toSet()!=ids || ids.size!=rule.requiredCheckIns)add(RunCompletionProblem.CHECKPOINTS)
            val broadEnd=(config.config["run_end_time"] as? JsonPrimitive)?.intOrNull
            if(broadEnd==null)add(RunCompletionProblem.RULES)
            else if(end.toLocalDate()!=begin.toLocalDate() || end.toLocalTime().toSecondOfDay()>minOf(rule.endSecond,broadEnd))add(RunCompletionProblem.TIME)
        }
    }
    override fun toString()="AutomaticRunSnapshot(generated)"
}
