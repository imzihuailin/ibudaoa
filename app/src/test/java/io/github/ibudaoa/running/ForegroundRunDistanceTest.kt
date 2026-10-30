package io.github.ibudaoa.running

import io.github.ibudaoa.domain.CampusCoordinate
import io.github.ibudaoa.domain.PlatformDistance
import io.github.ibudaoa.maps.DisplayFix
import kotlin.math.hypot
import org.junit.Assert.*
import org.junit.Test

class ForegroundRunDistanceTest {
    private val metric=object:PlatformDistance {
        override val implementationId="synthetic-flat-test"
        override fun meters(from:CampusCoordinate,to:CampusCoordinate)=hypot(to.latitude-from.latitude,to.longitude-from.longitude)*100_000
    }
    private fun fix(x:Double,y:Double,time:Long,accuracy:Float=3f)=DisplayFix(CampusCoordinate(y/100_000,x/100_000),accuracy,time,1_700_000_000_000+time)
    @Test fun `approach and detours accumulate from start without any route or snapping input`() {
        val tracker=ForegroundRunDistance(metric)
        assertTrue(tracker.start(fix(-20.0,30.0,1000),1000))
        for(p in listOf(fix(-20.0,10.0,6000),fix(0.0,10.0,11000),fix(20.0,10.0,16000),fix(40.0,0.0,21000))) tracker.sample(p,p.receivedElapsedMillis)
        assertEquals(60+hypot(20.0,10.0),tracker.summary.meters,0.00001)
        assertEquals(4,tracker.summary.segments)
    }
    @Test fun `stationary jitter is ignored but several small genuine movements accumulate`() {
        val tracker=ForegroundRunDistance(metric);tracker.start(fix(0.0,0.0,1000),1000)
        tracker.sample(fix(0.4,0.2,2000),2000);tracker.sample(fix(0.1,0.0,3000),3000)
        assertEquals(0.0,tracker.summary.meters,0.0)
        tracker.sample(fix(1.0,0.0,4000),4000);tracker.sample(fix(3.0,0.0,5000),5000)
        assertEquals(3.0,tracker.summary.meters,0.00001)
    }
    @Test fun `GPS jumps do not add outward or return teleport distances`() {
        val tracker=ForegroundRunDistance(metric);tracker.start(fix(0.0,0.0,1000),1000)
        tracker.sample(fix(1000.0,0.0,2000),2000)
        assertEquals(DistanceRejection.LOCATION_JUMP,tracker.summary.lastRejection)
        tracker.sample(fix(0.0,0.0,3000),3000);tracker.sample(fix(5.0,0.0,4000),4000)
        assertEquals(5.0,tracker.summary.meters,0.00001)
    }
    @Test fun `long GPS gaps establish a new anchor without inventing missing distance`() {
        val tracker=ForegroundRunDistance(metric);tracker.start(fix(0.0,0.0,1000),1000)
        tracker.sample(fix(60.0,0.0,21000),21000);assertEquals(0.0,tracker.summary.meters,0.0)
        assertEquals(1,tracker.summary.discontinuities)
        tracker.sample(fix(65.0,0.0,22000),22000);assertEquals(5.0,tracker.summary.meters,0.00001)
    }
    @Test fun `pause and background movement are never bridged into the recorded distance`() {
        val tracker=ForegroundRunDistance(metric);tracker.start(fix(0.0,0.0,1000),1000)
        tracker.sample(fix(5.0,0.0,2000),2000);tracker.pause()
        tracker.sample(fix(100.0,0.0,3000),3000)
        assertTrue(tracker.resume(fix(100.0,0.0,4000),4000))
        tracker.sample(fix(105.0,0.0,5000),5000);tracker.onBackground()
        tracker.sample(fix(200.0,0.0,6000),6000)
        assertFalse(tracker.resume(fix(200.0,0.0,7000),7000))
        assertTrue(tracker.resume(fix(200.0,0.0,7000),7000,platformReconciled=true))
        tracker.sample(fix(205.0,0.0,8000),8000)
        assertEquals(15.0,tracker.summary.meters,0.00001)
    }
    @Test fun `duplicate SDK fixes stale times and invalid accuracy cannot add distance`() {
        val tracker=ForegroundRunDistance(metric);val first=fix(0.0,0.0,1000);tracker.start(first,1000)
        tracker.sample(fix(5.0,0.0,2000).copy(sdkTimeMillis=first.sdkTimeMillis),2000)
        assertEquals(DistanceRejection.DUPLICATE_TIME,tracker.summary.lastRejection)
        tracker.sample(fix(5.0,0.0,900),2000);assertEquals(DistanceRejection.OUT_OF_ORDER,tracker.summary.lastRejection)
        tracker.sample(fix(5.0,0.0,3000,51f),3000);assertEquals(DistanceRejection.INVALID_FIX,tracker.summary.lastRejection)
        assertEquals(0.0,tracker.summary.meters,0.0)
        assertFalse(ForegroundRunDistance(metric).start(first.copy(sdkTimeMillis=null),1000))
    }
    @Test fun `finishing freezes the total and quality errors do not expose positions`() {
        val tracker=ForegroundRunDistance(metric);tracker.start(fix(0.0,0.0,1000),1000)
        tracker.sample(fix(5.0,0.0,2000),2000);tracker.finish();tracker.sample(fix(10.0,0.0,3000),3000)
        assertEquals(5.0,tracker.summary.meters,0.00001)
        assertEquals(DistancePhase.FINISHED,tracker.summary.phase)
        assertFalse(tracker.summary.toString().contains("CampusCoordinate"))
        assertFalse(tracker.summary.toString().contains("sdkTime"))
    }
    @Test fun `queued fixes use original SDK timing rather than UI callback burst speed`() {
        val tracker=ForegroundRunDistance(metric)
        tracker.start(fix(0.0,0.0,1000).copy(receivedElapsedMillis=5000),5000)
        tracker.sample(fix(5.0,0.0,2000).copy(receivedElapsedMillis=5000),5000)
        tracker.sample(fix(10.0,0.0,3000).copy(receivedElapsedMillis=5001),5001)
        assertEquals(10.0,tracker.summary.meters,0.00001)
    }
}
