package io.github.ibudaoa.running

import io.github.ibudaoa.capture.*
import io.github.ibudaoa.diagnostics.PreparationExport
import io.github.ibudaoa.domain.*
import io.github.ibudaoa.roads.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.IvParameterSpec
import org.junit.Assert.*
import org.junit.Test

class AutomaticRunTest {
    private val metric=object:PlatformDistance {
        override val implementationId="automatic-flat-test"
        override fun meters(from:CampusCoordinate,to:CampusCoordinate)=kotlin.math.hypot(to.latitude-from.latitude,to.longitude-from.longitude)*100_000
    }
    private fun p(meters:Double)=CampusCoordinate(30.0+meters/100_000,114.0)
    private val config=UploadFixture.config.copy(config=JsonObject(UploadFixture.config.config+mapOf("gyroscope_type" to JsonPrimitive(1))))
    private val points=mapOf("21" to p(0.0),"22" to p(100.0))
    private fun route()=FixedPointRoute(CandidateRoadRequest.create(config,1,"21","22").scope,"a".repeat(64),listOf("21","22"),"origin",listOf("21","22"),
        listOf(ObservedRoadEdge("origin","21",listOf(p(-100.0),p(0.0)),100.0),ObservedRoadEdge("21","22",listOf(p(0.0),p(100.0)),100.0)),
        200.0,200.0,150.0,1000.0,includesReviewedOrigin=true,originAccessMeters=100.0)
    private fun timeline()=AutomaticRouteRun(route(),points,5.0,metric,200)
    private class Journal:RunTaskJournal {
        var bytes:ByteArray?=null
        override fun load()=bytes?.clone()
        override fun save(bytes:ByteArray) { this.bytes=bytes.clone() }
        override fun clear() { bytes=null }
        override fun archiveResolved(bytes:ByteArray) {}
    }
    private inner class Harness {
        var clock=1000L;var bound=true;var prepared=true;var fail:RunEndpoint?=null
        var during:(RunEndpoint)->Unit={}
        val requests=mutableListOf<RunEndpoint>();val fields=mutableListOf<Pair<RunEndpoint,Map<String,String>>>()
        var uploads=0
        var credentialOnly=false
        val journal=Journal()
        val ledger=RunTaskLedger.create(journal,42,1,2,3,listOf("21","22"),generated=true,gyroRequired=true)
        val run=timeline()
        val gateway=AutomaticRunGateway(RunProtocolFixture.session(),config,
            RunRuntimeRequirements(RuntimeCheckShape.EMPTY_ARRAY,RuntimeCheckShape.EMPTY_ARRAY,true,true),
            now={UploadFixture.now},connection={object:AccountReadConnection {
                override fun send(request:LocalRequest):ByteArray {
                    val ep=RunEndpoint.entries.single { it.path==request.target };requests.add(ep)
                    fields.add(ep to RunProtocolFixture.fields(request));during(ep)
                    if(ep==fail)error("Synthetic network uncertainty")
                    return when(ep) {
                        RunEndpoint.REGISTER -> """{"status":1,"data":{"timestamp":1700000000,"str":"${"a".repeat(32)}","location_septal":20}}""".toByteArray()
                        RunEndpoint.STORAGE -> if(credentialOnly) {
                            val envelope=Json.parseToJsonElement(UploadFixture.grant().toString(Charsets.UTF_8)).jsonObject
                            JsonObject(envelope+mapOf("data" to JsonObject(envelope.getValue("data").jsonObject-setOf("bucket","endpoint")))).toString().toByteArray()
                        } else UploadFixture.grant()
                        RunEndpoint.FINISH -> """{"status":1,"data":{"record_id":99,"uid":42,"game_id":3,"start_time":1700000000,"record_status":1}}""".toByteArray()
                        RunEndpoint.RECORD -> """{"status":1,"data":{"id":99,"uid":42,"game_id":3,"start_time":1700000000,"type":1,"record_status":1}}""".toByteArray()
                        else -> """{"status":1,"data":[]}""".toByteArray()
                    }
                }
                override fun close() {}
            }},uploader={RunObjectUpload { uploads++;FakeRunHttps(it,etag=true) }})
        val controller=AutomaticRunController(ledger,run,gateway,true,{bound},{prepared},{clock})
        suspend fun complete() {
            assertTrue(controller.start())
            for(i in 1..100) {
                clock+=500;controller.tick()
                if(controller.summary.phase==AutomaticRunPhase.VALID)break
            }
        }
    }
    @Test fun `credential only storage response completes actual adapter chain and journals finish stage`()=runBlocking {
        val h=Harness();h.credentialOnly=true;h.complete()
        assertEquals(AutomaticRunPhase.VALID,h.controller.summary.phase)
        assertEquals(RunFinishStage.FINISH_RESPONSE,h.ledger.finishStage())
        assertFalse(h.ledger.canArchiveAfterManualNoRecord())
    }
    @Test fun `manual archive eligibility preserves uncertain registration and bound records`()=runBlocking {
        val failed=Harness();failed.fail=RunEndpoint.FINISH;assertTrue(failed.controller.start())
        failed.clock+=40_000;failed.controller.tick()
        assertTrue(failed.ledger.canArchiveAfterManualNoRecord())
        assertEquals(RunFinishStage.FINISH_REQUEST,failed.ledger.finishStage())
        val registration=Harness();registration.fail=RunEndpoint.REGISTER;assertFalse(registration.controller.start())
        assertFalse(registration.ledger.canArchiveAfterManualNoRecord())
        val complete=Harness();complete.complete();assertFalse(complete.ledger.canArchiveAfterManualNoRecord())
    }
    @Test fun `external document UI waits for execution but remains available for unresolved results`() {
        for(phase in AutomaticRunPhase.entries)assertEquals(phase.name,
            phase in setOf(AutomaticRunPhase.STARTING,AutomaticRunPhase.RUNNING,AutomaticRunPhase.PAUSED,AutomaticRunPhase.FINISHING),
            AutomaticRunSummary(phase).blocksExternalUi)
        val safe=PreparationExport.encode(config,1,1,null,automatic=AutomaticRunSummary(AutomaticRunPhase.INTERRUPTED,pendingWrite="FINISH"))
        assertEquals("FINISH",Json.parseToJsonElement(safe).jsonObject.getValue("automatic_execution").jsonObject.getValue("pending_write").jsonPrimitive.content)
        val invalid=PreparationExport.encode(config,1,1,null,automatic=AutomaticRunSummary(pendingWrite="sensitive-arbitrary-text"))
        assertFalse(invalid.contains("sensitive-arbitrary-text"))
    }
    @Test fun `display progress and remaining duration freeze while paused and resume from same coordinate`()=runBlocking {
        val h=Harness();assertTrue(h.controller.start())
        h.clock+=10_000;h.controller.tick()
        assertEquals(0.25f,h.controller.summary.progressFraction,0.001f)
        assertEquals(30L,h.controller.summary.remainingSeconds)
        val position=h.run.current()!!.coordinate
        h.controller.pause();h.clock+=20_000;h.controller.tick()
        assertEquals(position,h.run.current()!!.coordinate)
        assertEquals(30L,h.controller.summary.remainingSeconds)
        assertTrue(h.controller.resume());h.clock+=10_000;h.controller.tick()
        assertEquals(0.5f,h.controller.summary.progressFraction,0.001f)
        assertEquals(20L,h.controller.summary.remainingSeconds)
    }
    @Test fun `clock alone advances full reviewed route including approach without GPS or physical steps`() {
        val run=timeline();run.start(1000)
        run.advance(11000)
        assertEquals(50.0,run.current()!!.meters,0.001)
        assertEquals(p(-50.0).latitude,run.current()!!.coordinate.latitude,0.00000001)
        run.advance(41000)
        assertEquals(200.0,run.current()!!.meters,0.001)
        assertEquals(setOf("21","22"),run.checkpoints().map { it.id }.toSet())
        assertEquals(AutomaticRunPhase.FINISHING,run.phase)
        assertEquals(81,run.trace().size)
        assertTrue(run.trace().last().elapsedMillis>=run.trace().first().elapsedMillis)
    }
    @Test fun `pause retains fixed route and points without advancing virtual distance`() {
        val run=timeline();run.start(1000);run.advance(11000);run.pause(11000)
        run.advance(31000);assertEquals(50.0,run.current()!!.meters,0.001)
        run.resume(31000);run.advance(41000)
        assertEquals(100.0,run.current()!!.meters,0.001)
        assertEquals(40_000L,run.elapsedMillis)
        assertEquals(40_000L,run.current()!!.elapsedMillis)
    }
    @Test fun `foreground interruption cannot be resumed or generate missing progress`() {
        val run=timeline();run.start(1000);run.advance(11000);run.interrupt(11000)
        val count=run.trace().size;run.advance(100000)
        assertEquals(count,run.trace().size);assertEquals(50.0,run.current()!!.meters,0.001)
        assertTrue(runCatching { run.resume(100000) }.isFailure)
    }
    @Test fun `unreviewed geometry gap or changed point subset is refused before any start`() {
        val original=route()
        val changed=original.copy(legs=listOf(original.legs.first(),original.legs[1].copy(points=listOf(p(20.0),p(100.0)))))
        assertTrue(runCatching { AutomaticRouteRun(changed,points,5.0,metric,200) }.isFailure)
        assertTrue(runCatching { AutomaticRouteRun(original,points+mapOf("23" to p(50.0)),5.0,metric,200) }.isFailure)
    }
    @Test fun `automatic upload end association and exact verdict complete without sensor permission or movement`() = runBlocking {
        val h=Harness();h.complete()
        assertEquals(AutomaticRunPhase.VALID,h.controller.summary.phase)
        assertTrue(h.controller.summary.platformStarted);assertTrue(h.controller.summary.recordVerified)
        assertEquals(2,h.controller.summary.visitedPoints)
        assertEquals(2,h.uploads)
        assertEquals(1,h.requests.count { it==RunEndpoint.ZONE });assertEquals(1,h.requests.count { it==RunEndpoint.REGISTER })
        assertEquals(1,h.requests.count { it==RunEndpoint.FINISH });assertEquals(1,h.requests.count { it==RunEndpoint.GYROSCOPE })
        assertTrue(h.requests.indexOf(RunEndpoint.GYROSCOPE)<h.requests.indexOf(RunEndpoint.RECORD))
        val finish=h.fields.single { it.first==RunEndpoint.FINISH }.second
        assertEquals("0.20",finish["distance"])
        val logs=Json.parseToJsonElement(finish.getValue("log_data")).jsonArray
        assertEquals(listOf("21","22"),logs.map { it.jsonObject.getValue("point_id").jsonPrimitive.content })
        assertTrue(logs.all { it.jsonObject.getValue("time").jsonPrimitive.long>0 })
        assertTrue(h.journal.bytes!!.toString(Charsets.UTF_8).contains("AUTOMATIC"))
    }
    @Test fun `a newly passed checkpoint uploads its virtual position before the next regular heartbeat`() = runBlocking {
        val h=Harness();assertTrue(h.controller.start())
        h.clock+=500;h.controller.tick()
        h.clock+=18_500;h.controller.tick()
        val positions=h.fields.filter { it.first==RunEndpoint.LOCATION }.map { it.second }
        assertEquals(2,positions.size)
        assertTrue(kotlin.math.abs(positions.last().getValue("latitude").toDouble()-points.getValue("21").latitude)<0.00006)
    }
    @Test fun `unknown registration result fences all repeated starts and later writes`() = runBlocking {
        val h=Harness();h.fail=RunEndpoint.REGISTER
        assertFalse(h.controller.start());assertFalse(h.controller.start())
        h.clock+=20_000;h.controller.tick()
        assertEquals(AutomaticRunPhase.INTERRUPTED,h.controller.summary.phase)
        assertEquals(1,h.requests.count { it==RunEndpoint.REGISTER });assertEquals(0,h.uploads)
    }
    @Test fun `late registration after background preserves receipt but cannot start automatic progress`() = runBlocking {
        val h=Harness();h.during={ if(it==RunEndpoint.REGISTER)h.controller.interrupt() }
        assertFalse(h.controller.start());assertTrue(h.controller.summary.platformStarted)
        assertEquals(AutomaticRunPhase.INTERRUPTED,h.controller.summary.phase)
        h.clock+=30_000;h.controller.returned();h.controller.tick()
        assertTrue(h.run.trace().isEmpty());assertFalse(h.controller.resume())
    }
    @Test fun `expired preparation does not issue any task request`() = runBlocking {
        val h=Harness();h.prepared=false
        assertFalse(h.controller.start());assertTrue(h.requests.isEmpty());assertTrue(h.ledger.canAbandonUnsent())
        h.ledger.abandonUnsent();assertNull(h.journal.bytes)
    }
    @Test fun `automatic records cannot be accepted before required motion association acknowledgement`() = runBlocking {
        val h=Harness();assertTrue(h.controller.start());h.clock+=40_000
        h.fail=RunEndpoint.GYROSCOPE;h.controller.tick()
        assertFalse(h.controller.summary.recordVerified);assertTrue(h.controller.summary.recordBound)
        assertEquals(0,h.requests.count { it==RunEndpoint.RECORD })
        assertTrue(h.ledger.summary.needsReconciliation)
    }
    @Test fun `generated payload remains distinct from device observations and diagnostics contain no coordinates or identity`() = runBlocking {
        val h=Harness();h.complete()
        val text=PreparationExport.encode(config,1,1,null,automatic=h.controller.summary)
        val report=Json.parseToJsonElement(text).jsonObject.getValue("automatic_execution").jsonObject
        assertEquals("GENERATED_ROUTE",report.getValue("mode").jsonPrimitive.content)
        assertEquals(true,report.getValue("record_verified").jsonPrimitive.boolean)
        assertFalse(report.toString().contains("latitude"));assertFalse(report.toString().contains("record_id"));assertFalse(report.toString().contains("1700000000"))
        assertFalse(h.run.current()!!.toString().contains("CampusCoordinate"))
    }
    @Test fun `motion file is generated virtual kinematics and both files match the same automatic trace`() {
        val run=timeline();run.start(1000);run.advance(41000);run.freeze(41000)
        val context=RunRequestContext(42,1,2,3,listOf("21","22"),1700000000,"a".repeat(32))
        AutomaticRunFiles.build(context,config,run,UploadFixture.key).use { files ->
            val cipher=Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE,SecretKeySpec("Wet2C8d34f62ndi3".toByteArray(),"AES"),IvParameterSpec("K6iv85jBD8jgf32D".toByteArray()))
            val track=Json.parseToJsonElement(cipher.doFinal(Base64.getMimeDecoder().decode(files.track)).toString(Charsets.UTF_8)).jsonArray
            val motion=Json.parseToJsonElement(files.motion.toString(Charsets.UTF_8)).jsonArray
            assertEquals(run.trace().size,track.size);assertEquals(track.size,motion.size)
            assertEquals(1700000040000L,motion.last().jsonObject.getValue("t").jsonPrimitive.long)
            assertEquals(run.trace().first().coordinate.latitude.toFloat().toDouble(),track.first().jsonObject.getValue("a").jsonPrimitive.double,0.0)
            assertEquals("250",files.fields["step_num"])
        }
    }
    @Test fun `controller pause and continue preserve fixed IDs and exclude pause from route advancement`() = runBlocking {
        val h=Harness();assertTrue(h.controller.start());h.clock+=10_000;h.controller.tick();h.controller.pause()
        val paused=h.run.current()!!.meters;h.clock+=20_000;h.controller.tick()
        assertEquals(paused,h.run.current()!!.meters,0.0)
        assertEquals(AutomaticRunPhase.PAUSED,h.controller.summary.phase)
        assertTrue(h.controller.resume());h.clock+=10_000;h.controller.tick()
        assertEquals(100.0,h.run.current()!!.meters,0.001)
        assertEquals(listOf("21","22"),h.ledger.requestContext().points)
        assertEquals(1,h.requests.count { it==RunEndpoint.REGISTER })
    }
    @Test fun `only a provably unsent intent can be discarded after interruption`() {
        val journal=Journal();val ledger=RunTaskLedger.create(journal,42,1,2,3,listOf("21","22"),generated=true)
        ledger.background();assertTrue(ledger.canAbandonUnsent());ledger.abandonUnsent();assertNull(journal.bytes)
        val next=RunTaskLedger.create(journal,42,1,2,3,listOf("21","22"),generated=true)
        next.begin(RunWrite.SELECT_ZONE);assertFalse(next.canAbandonUnsent())
        assertTrue(runCatching { next.abandonUnsent() }.isFailure);assertNotNull(journal.bytes)
    }
    @Test fun `invalid platform status zero with an explicit failure reason is retained as an invalid record`() {
        val expected=ExpectedRunRecord(99,42,3,1700000000)
        val body="""{"status":1,"data":{"id":99,"uid":42,"game_id":3,"start_time":1700000000,"type":1,"record_status":0,"log_num":0,"record_failed_reason":"打卡次数不足"}}""".toByteArray()
        val result=RunRecordVerifier.decode(body,expected)
        assertEquals(RunRecordVerdict.INVALID,result.verdict);assertEquals(0,result.serverPointCount);assertEquals(0,result.serverStatus)
        assertFalse(result.platformValid)
    }

    @Test fun `supported no fence client does not invalidate the current position approach outside the drawn boundary`() {
        val smaller=config.copy(config=JsonObject(config.config+mapOf("run_zone_latlng" to JsonArray(listOf(
            "113.99,29.9995","114.01,29.9995","114.01,30.0015","113.99,30.0015").map(::JsonPrimitive)))))
        val run=timeline();run.start(1000);run.advance(41000);run.freeze(41000)
        val context=RunRequestContext(42,1,2,3,listOf("21","22"),1700000000,"a".repeat(32))
        AutomaticRunFiles.build(context,smaller,run,UploadFixture.key).use {
            assertEquals("1",it.fields["is_running_area_valid"])
            assertEquals(it.fields["pass_total"],it.fields["pass_effective_num"])
            assertEquals("0.20",it.fields["distance"])
        }
    }

    @Test fun `whole second protocol duration cannot round a short automatic run faster than its target pace`() {
        val short=route().copy(legs=listOf(ObservedRoadEdge("origin","21",listOf(p(-10.0),p(0.0)),10.0),
            ObservedRoadEdge("21","22",listOf(p(0.0),p(0.1)),0.1)),minimumTargetMeters=5.0)
        val run=AutomaticRouteRun(short,mapOf("21" to p(0.0),"22" to p(0.1)),1.0,metric,200)
        assertEquals(3000L,run.durationMillis)
        assertTrue(run.durationMillis/run.totalMeters>=200)
    }

}
