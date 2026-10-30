package io.github.ibudaoa.running

import io.github.ibudaoa.domain.*
import io.github.ibudaoa.maps.DisplayFix
import kotlin.math.hypot
import org.junit.Assert.*
import org.junit.Test

class ForegroundRunProgressTest {
    private val metric=object:PlatformDistance {
        override val implementationId="synthetic-flat"
        override fun meters(from:CampusCoordinate,to:CampusCoordinate)=hypot(to.latitude-from.latitude,to.longitude-from.longitude)*100_000
    }
    private fun point(x:Double,y:Double)=CampusCoordinate(y/100_000,x/100_000)
    private fun fix(x:Double,y:Double,t:Long)=DisplayFix(point(x,y),3f,t,1_700_000_000_000+t)
    private fun progress()=ForegroundRunProgress(mapOf("a" to point(20.0,10.0),"b" to point(40.0,0.0)),5.0,metric)
    @Test fun `approach and off line detours count while local visits remain separate from platform check ins`() {
        val run=progress();assertTrue(run.start(fix(-20.0,30.0,1000),1000))
        for(p in listOf(fix(-20.0,10.0,6000),fix(0.0,10.0,11000),fix(20.0,10.0,16000),fix(40.0,0.0,21000))) run.sample(p,p.receivedElapsedMillis)
        assertEquals(60+hypot(20.0,10.0),run.summary.distance.meters,0.0001)
        assertEquals(2,run.summary.observedPoints);assertEquals(0,run.summary.platformConfirmedPoints)
        assertEquals(20,run.summary.elapsedSeconds)
    }
    @Test fun `jumping to a checkpoint duplicate SDK callbacks and poor fixes cannot count a visit`() {
        val run=progress();run.start(fix(0.0,0.0,1000),1000)
        run.sample(fix(40.0,0.0,2000),2000)
        assertEquals(0,run.summary.observedPoints)
        run.sample(fix(20.0,10.0,3000).copy(accuracyMeters=80f),3000)
        run.sample(fix(20.0,10.0,4000).copy(sdkTimeMillis=1_700_000_002_000),4000)
        assertEquals(0,run.summary.observedPoints)
    }
    @Test fun `pause time counts but paused travel cannot count distance or visits`() {
        val run=progress();run.start(fix(0.0,0.0,1000),1000);run.pause(2000)
        run.sample(fix(40.0,0.0,6000),6000)
        assertEquals(0,run.summary.observedPoints);assertEquals(0.0,run.summary.distance.meters,0.0)
        assertEquals(5,run.summary.elapsedSeconds)
        assertTrue(run.resume(fix(0.0,0.0,7000),7000));run.sample(fix(20.0,10.0,12000),12000)
        assertEquals(1,run.summary.observedPoints);assertEquals(11,run.summary.elapsedSeconds)
    }
    @Test fun `returning from background never resumes or invents a bridge`() {
        val run=progress();run.start(fix(0.0,0.0,1000),1000);run.background(2000)
        run.sample(fix(40.0,0.0,7000),7000)
        assertEquals(0,run.summary.observedPoints)
        assertFalse(run.resume(fix(40.0,0.0,8000),8000))
        assertEquals(DistancePhase.INTERRUPTED,run.summary.distance.phase)
    }
    @Test fun `finishing freezes progress time and visits and exposes no positions`() {
        val run=progress();run.start(fix(0.0,0.0,1000),1000);run.finish(4000)
        run.sample(fix(20.0,10.0,9000),9000);run.tick(10000)
        assertEquals(3,run.summary.elapsedSeconds);assertEquals(0,run.summary.observedPoints)
        assertFalse(run.summary.toString().contains("CampusCoordinate"))
    }
    @Test fun `checkpoint map is fixed even if the caller mutates its original collection`() {
        val original=mutableMapOf("a" to point(20.0,10.0),"b" to point(40.0,0.0))
        val run=ForegroundRunProgress(original,5.0,metric);original["a"]=point(0.0,0.0);original["c"]=point(0.0,0.0)
        run.start(fix(0.0,0.0,1000),1000)
        assertEquals(2,run.summary.requiredPoints);assertEquals(0,run.summary.observedPoints)
    }
    @Test fun `trace preserves genuine segment boundaries and rejects paused or fabricated positions`() {
        val run=progress();run.start(fix(0.0,0.0,1000),1000)
        run.sample(fix(5.0,0.0,2000),2000);run.pause(3000)
        run.sample(fix(100.0,0.0,4000),4000)
        assertTrue(run.resume(fix(100.0,0.0,5000),5000))
        run.sample(fix(105.0,0.0,6000),6000)
        val trace=run.traceSnapshot()
        assertEquals(4,trace.size);assertEquals(listOf(true,false,true,false),trace.map { it.startsSegment })
        assertEquals(10.0,trace.last().cumulativeMeters,0.0001)
        assertFalse(trace.toString().contains("CampusCoordinate"))
    }
    @Test fun `trace capacity stops measurement rather than silently dropping evidence`() {
        val run=ForegroundRunProgress(mapOf("a" to point(20.0,0.0),"b" to point(40.0,0.0)),5.0,metric,maxTraceFixes=2)
        run.start(fix(0.0,0.0,1000),1000);run.sample(fix(5.0,0.0,2000),2000)
        run.sample(fix(10.0,0.0,3000),3000)
        assertEquals(DistancePhase.INTERRUPTED,run.summary.distance.phase)
        assertEquals(2,run.traceSnapshot().size);assertEquals(5.0,run.summary.distance.meters,0.0)
        assertNull(run.acceptedFix())
    }

}
