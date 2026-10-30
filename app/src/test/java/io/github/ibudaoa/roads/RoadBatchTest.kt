package io.github.ibudaoa.roads

import io.github.ibudaoa.maps.AmapDistance
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RoadBatchTest {
    @Test fun `fixed star covers every candidate once including outside endpoint without selecting run points`() {
        val plan = RoadBatchPlan.create(RoadFixture.config(), 1)
        assertEquals(3, plan.candidateCount)
        assertEquals(2, plan.requests.size)
        assertEquals(setOf("a", "b", "outside"), plan.requests.flatMap { listOf(it.fromId, it.toId) }.toSet())
        assertEquals(1, plan.requests.map { it.fromId }.distinct().size)
    }

    @Test fun `successful checking reports findings and progress without claiming executable route`() = runBlocking {
        val plan = RoadBatchPlan.create(RoadFixture.config(), 1)
        val updates = mutableListOf<RoadBatchReport>()
        val final = RoadBatchRunner.run(plan, AmapDistance(), probe = { r ->
            val distance = AmapDistance().meters(r.from, r.to)
            RoadLinkOutcome.Received(WalkingReply(r, WalkingRoadReviewer.SOURCE,
                listOf(WalkingLeg(distance, listOf(listOf(r.from, r.to))))))
        }, publish = updates::add)
        assertEquals(RoadBatchPhase.COMPLETE, final.phase)
        assertEquals(2, final.links.size)
        assertEquals(true, final.links.last().leavesBoundary)
        assertEquals(listOf(0, 1, 2, 2), updates.map { it.links.size })
    }

    @Test fun `road evidence callback receives only successfully reviewed SDK legs`() = runBlocking {
        val plan = RoadBatchPlan.create(RoadFixture.config(), 1)
        val evidence = mutableListOf<CandidateRoadEvidence>()
        val report = RoadBatchRunner.run(plan, AmapDistance(), probe = { r ->
            if (r.toId == "b") RoadLinkOutcome.Received(WalkingReply(r, WalkingRoadReviewer.SOURCE,
                listOf(WalkingLeg(222.39, listOf(listOf(r.from, r.to))))))
            else RoadLinkOutcome.Failed(RoadLinkFailure.NO_ROUTE)
        }, publish = {}, paceMillis = 0, observe = { r, leg -> evidence.add(CandidateRoadEvidence(r.fromId,r.toId,leg)) })
        assertEquals(RoadBatchPhase.COMPLETE, report.phase)
        assertEquals(listOf("b"), evidence.map { it.toId })
        assertEquals(1, report.links.count { it.failure == RoadLinkFailure.NO_ROUTE })
    }

    @Test fun `global cancellation is propagated and cannot publish completed or start later requests`() = runBlocking {
        val plan = RoadBatchPlan.create(RoadFixture.config(), 1)
        val entered = CompletableDeferred<Unit>()
        val updates = mutableListOf<RoadBatchReport>()
        var calls = 0
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            RoadBatchRunner.run(plan, AmapDistance(), probe = {
                calls++; entered.complete(Unit); awaitCancellation()
            }, publish = updates::add)
        }
        entered.await(); job.cancelAndJoin()
        assertEquals(1, calls)
        assertTrue(updates.none { it.phase == RoadBatchPhase.COMPLETE })
    }

    @Test fun `SDK error stops further requests but no-route stays a per-link finding`() = runBlocking {
        val plan = RoadBatchPlan.create(RoadFixture.config(), 1)
        var calls = 0
        val failed = RoadBatchRunner.run(plan, AmapDistance(), { calls++; RoadLinkOutcome.Failed(RoadLinkFailure.SDK_ERROR) }, {})
        assertEquals(1, calls); assertEquals(RoadBatchPhase.FAILED, failed.phase)
        val missing = RoadBatchRunner.run(plan, AmapDistance(), { RoadLinkOutcome.Failed(RoadLinkFailure.NO_ROUTE) }, {})
        assertEquals(RoadBatchPhase.COMPLETE, missing.phase)
        assertEquals(2, missing.links.count { it.failure == RoadLinkFailure.NO_ROUTE })
    }

    @Test fun `per-call timeout is a finding while outer timeout cancels the batch`() = runBlocking {
        val plan = RoadBatchPlan.create(RoadFixture.config(), 1)
        val timeout = RoadBatchRunner.run(plan, AmapDistance(), {
            withTimeout(5) { awaitCancellation() }
        }, {})
        assertEquals(2, timeout.links.count { it.failure == RoadLinkFailure.TIMEOUT })
        val updates = mutableListOf<RoadBatchReport>()
        val outer = runCatching { withTimeout(5) {
            RoadBatchRunner.run(plan, AmapDistance(), { awaitCancellation() }, updates::add)
        } }
        assertTrue(outer.exceptionOrNull() is TimeoutCancellationException)
        assertTrue(updates.none { it.phase == RoadBatchPhase.COMPLETE })
    }
}
