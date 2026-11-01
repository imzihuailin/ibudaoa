package io.github.ibudaoa.running

import io.github.ibudaoa.capture.*
import io.github.ibudaoa.diagnostics.PreparationExport
import io.github.ibudaoa.domain.*
import io.github.ibudaoa.roads.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
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
    private fun requirements()=RunRuntimeRequirements(RuntimeCheckShape.EMPTY_ARRAY,RuntimeCheckShape.EMPTY_ARRAY,true,true,
        RuntimeSwitch.DISABLED,RuntimeSwitch.DISABLED,RuntimeSwitch.DISABLED)
    private inner class Harness(requirements:RunRuntimeRequirements=requirements()) {
        var clock=1000L;var bound=true;var prepared=true;var fail:RunEndpoint?=null
        var during:(RunEndpoint)->Unit={}
        val requests=mutableListOf<RunEndpoint>();val fields=mutableListOf<Pair<RunEndpoint,Map<String,String>>>()
        var uploads=0
        var failUploadAt:Int?=null
        var credentialOnly=false
        var serverStart=1700000000L
        val journal=Journal()
        val ledger=RunTaskLedger.create(journal,42,1,2,3,listOf("21","22"),generated=true,gyroRequired=true)
        val run=timeline()
        val gateway=AutomaticRunGateway(RunProtocolFixture.session(),config,
            requirements,
            now={UploadFixture.now},connection={object:AccountReadConnection {
                override fun send(request:LocalRequest):ByteArray {
                    val ep=RunEndpoint.entries.single { it.path==request.target };requests.add(ep)
                    fields.add(ep to RunProtocolFixture.fields(request));during(ep)
                    if(ep==fail)error("Synthetic network uncertainty")
                    return when(ep) {
                        RunEndpoint.REGISTER -> """{"status":1,"data":{"timestamp":$serverStart,"str":"${"a".repeat(32)}","location_septal":20}}""".toByteArray()
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
            }},uploader={RunObjectUpload { uploads++;if(uploads==failUploadAt)error("Synthetic upload failure");FakeRunHttps(it,etag=true) }})
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
        val run=timeline();run.start(1000);run.advance(41000);run.freeze(41000,UploadFixture.context)
        val context=RunRequestContext(42,1,2,3,listOf("21","22"),1700000000,"a".repeat(32))
        AutomaticRunFiles.build(context,config,run,UploadFixture.key).use { files ->
            val cipher=Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE,SecretKeySpec("Wet2C8d34f62ndi3".toByteArray(),"AES"),IvParameterSpec("K6iv85jBD8jgf32D".toByteArray()))
            val track=Json.parseToJsonElement(cipher.doFinal(Base64.getMimeDecoder().decode(files.track)).toString(Charsets.UTF_8)).jsonArray
            val motion=Json.parseToJsonElement(files.motion.toString(Charsets.UTF_8)).jsonArray
            assertEquals(run.trace().size,track.size);assertEquals(track.size,motion.size)
            val format=java.text.DecimalFormat("0.000000",java.text.DecimalFormatSymbols(java.util.Locale.ROOT))
            track.zip(motion).forEach { (row,vector) ->
                assertEquals(format.format(row.jsonObject.getValue("o").jsonPrimitive.double),vector.jsonObject.getValue("n").jsonPrimitive.content)
                assertEquals(format.format(row.jsonObject.getValue("a").jsonPrimitive.double),vector.jsonObject.getValue("a").jsonPrimitive.content)
            }
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
        val run=timeline();run.start(1000);run.advance(41000);run.freeze(41000,UploadFixture.context)
        val context=RunRequestContext(42,1,2,3,listOf("21","22"),1700000000,"a".repeat(32))
        AutomaticRunFiles.build(context,smaller,run,UploadFixture.key).use {
            assertEquals("1",it.fields["is_running_area_valid"])
            assertEquals(it.fields["pass_total"],it.fields["pass_effective_num"])
            assertEquals("0.20",it.fields["distance"])
        }
    }

    @Test fun `unsupported or unknown device policy makes zero task requests`()=runBlocking {
        for(policy in listOf(requirements().copy(deviceCheck=RuntimeSwitch.ENABLED),requirements().copy(geeDeviceCheck=RuntimeSwitch.MISSING),
            requirements().copy(timeCheck=RuntimeSwitch.INVALID),requirements().copy(runChecks=RuntimeCheckShape.NONEMPTY_ARRAY))) {
            val h=Harness(policy)
            assertFalse(h.controller.start());assertTrue(h.requests.isEmpty());assertEquals(0,h.uploads)
            assertTrue(h.ledger.canAbandonUnsent())
        }
    }
    @Test fun `preparation changes after zone response preserves task without registering`()=runBlocking {
        val h=Harness();h.during={ if(it==RunEndpoint.ZONE)h.prepared=false }
        assertFalse(h.controller.start());assertEquals(listOf(RunEndpoint.ZONE),h.requests)
        assertEquals("PREPARATION_CHANGED",h.controller.summary.problem)
        assertFalse(h.ledger.canAbandonUnsent());assertFalse(h.controller.start())
    }
    @Test fun `enabled server time check honors official ten minute boundary and preserves rejected registration`()=runBlocking {
        for(offset in listOf(-600L,0L,600L)) {
            val h=Harness(requirements().copy(timeCheck=RuntimeSwitch.ENABLED));h.serverStart=UploadFixture.now.epochSecond+offset
            assertTrue(h.controller.start())
        }
        for(offset in listOf(-601L,601L)) {
            val h=Harness(requirements().copy(timeCheck=RuntimeSwitch.ENABLED));h.serverStart=UploadFixture.now.epochSecond+offset
            assertFalse(h.controller.start());assertTrue(h.controller.summary.platformStarted)
            assertEquals("REGISTRATION_TIME_MISMATCH",h.controller.summary.problem)
            assertTrue(h.run.trace().isEmpty());assertEquals(0,h.uploads)
            assertFalse(h.controller.start());assertEquals(1,h.requests.count { it==RunEndpoint.REGISTER })
        }
    }
    @Test fun `finish snapshot excludes delayed tick and storage request time and is frozen only once`()=runBlocking {
        val h=Harness();assertTrue(h.controller.start());h.clock+=45_000
        h.during={ if(it==RunEndpoint.STORAGE) { h.clock+=30_000;h.run.freeze(h.clock,UploadFixture.context) } }
        h.controller.tick()
        val fields=h.fields.single { it.first==RunEndpoint.FINISH }.second
        assertEquals("40",fields["used_time"]);assertEquals("1700000040",fields["end_time"])
        assertEquals(40_000L,h.run.completion().samples.last().elapsedMillis)
        assertEquals(40L,h.controller.summary.elapsedSeconds)
    }
    @Test fun `pause boundaries keep stationary minutes empty and file timestamps agree with total duration`() {
        val run=timeline();run.start(1000);run.advance(11000);run.pause(11000)
        run.advance(131000);run.resume(131000);run.advance(161000);run.freeze(191000,UploadFixture.context)
        val snapshot=run.completion();snapshot.validate(UploadFixture.context)
        assertEquals(160_000L,snapshot.elapsedMillis)
        assertEquals(0,snapshot.minuteSteps()[1])
        val anchor=snapshot.samples.single { it.elapsedMillis==130_000L }
        assertEquals(0.0,anchor.speed,0.0);assertEquals(50.0,anchor.meters,0.001)
        assertEquals(250,snapshot.minuteSteps().sum())
        AutomaticRunFiles.build(UploadFixture.context,config,snapshot,UploadFixture.key).use { files ->
            val motion=Json.parseToJsonElement(files.motion.toString(Charsets.UTF_8)).jsonArray
            assertEquals(1700000160000L,motion.last().jsonObject.getValue("t").jsonPrimitive.long)
            assertEquals("160",files.fields["used_time"])
        }
    }
    @Test fun `ending during a pause keeps its time without inventing paused distance or steps`() {
        val run=timeline();run.start(1000);run.advance(11000);run.pause(11000);run.freeze(131000,UploadFixture.context)
        val s=run.completion();s.validate(UploadFixture.context)
        assertEquals(130_000L,s.elapsedMillis);assertEquals(10_000L,s.activeMillis)
        assertEquals(50.0,s.meters,0.001);assertEquals(62,s.minuteSteps().sum())
        run.freeze(231000,UploadFixture.context);assertSame(s,run.completion())
        assertTrue(runCatching { run.resume(231000) }.isFailure)
    }
    @Test fun `malformed or mixed completion is rejected before authorization or file upload`()=runBlocking {
        val h=Harness();h.run.start(1000);h.run.advance(41000);h.run.freeze(41000,UploadFixture.context)
        val s=h.run.completion()
        val corrupt=listOf(
            AutomaticRunSnapshot(s.samples.reversed(),s.visits,s.ids,s.elapsedMillis,s.activeMillis,UploadFixture.context),
            AutomaticRunSnapshot(s.samples,s.visits+listOf(s.visits.first()),s.ids,s.elapsedMillis,s.activeMillis,UploadFixture.context),
            AutomaticRunSnapshot(s.samples,s.visits,s.ids,s.elapsedMillis+1000,s.activeMillis,UploadFixture.context))
        corrupt.forEach { assertTrue(runCatching { it.validate(UploadFixture.context) }.exceptionOrNull() is RunMaterialException) }
        val wrong=RunRequestContext(42,1,2,3,listOf("21","99"),1700000000,"a".repeat(32))
        assertTrue(runCatching { h.gateway.write(RunWrite.FINISH,wrong,h.run) }.exceptionOrNull() is RunMaterialException)
        assertTrue(h.requests.isEmpty());assertEquals(0,h.uploads)
    }
    @Test fun `historical missed points are not flushed at finish as current positions`()=runBlocking {
        val h=Harness();assertTrue(h.controller.start());h.clock+=40_000;h.controller.tick()
        val positions=h.fields.filter { it.first==RunEndpoint.LOCATION }
        assertEquals(1,positions.size)
        assertEquals(p(100.0).latitude,positions.single().second.getValue("latitude").toDouble(),0.000000001)
        assertEquals(1,h.controller.summary.acknowledgedPoints)
        assertTrue(RunCompletionProblem.CHECKPOINT_REPORTS in h.controller.summary.ruleProblems)
        assertEquals(1,h.ledger.acknowledgedPoints())
    }
    @Test fun `frozen material cannot be mutated or rebound to a different account registration or point order`() {
        val run=timeline();run.start(1000);run.advance(41000);run.freeze(41000,UploadFixture.context)
        val s=run.completion()
        assertTrue(runCatching { (s.samples as MutableList).clear() }.isFailure)
        assertTrue(runCatching { (s.ids as MutableSet).clear() }.isFailure)
        for(context in listOf(RunRequestContext(43,1,2,3,listOf("21","22"),1700000000,"a".repeat(32)),
            RunRequestContext(42,1,2,3,listOf("21","22"),1700000001,"a".repeat(32)),
            RunRequestContext(42,1,2,3,listOf("22","21"),1700000000,"a".repeat(32)))) {
            assertTrue(runCatching { s.validate(context) }.exceptionOrNull() is RunMaterialException)
        }
        assertEquals("AutomaticRunSnapshot(generated)",s.toString())
    }
    @Test fun `completion checks honor inclusive closing time and expose pause induced pace failure`() {
        val rule=buildJsonObject {
            put("start_time",21600);put("end_time",79200);put("min_distance",0.1);put("max_distance",1)
            put("max_pace",180);put("min_pace",540);put("min_log_num",2);put("point_num_online",2);put("point_num_offline",0)
        }
        val cfg=config.copy(config=JsonObject(config.config+("time_rule_arr" to JsonArray(listOf(rule)))))
        val run=timeline();run.start(1000);run.advance(41000);run.freeze(41000,UploadFixture.context)
        val close=java.time.Instant.parse("2026-10-29T13:59:20Z").epochSecond
        assertTrue(run.completion().ruleProblems(cfg,close).isEmpty())
        assertTrue(RunCompletionProblem.TIME in run.completion().ruleProblems(cfg,close+1))
        val paused=timeline();paused.start(1000);paused.advance(11000);paused.pause(11000)
        paused.resume(131000);paused.advance(161000);paused.freeze(161000,UploadFixture.context)
        assertTrue(RunCompletionProblem.PACE in paused.completion().ruleProblems(cfg,UploadFixture.now.epochSecond))
        assertFalse(RunCompletionProblem.DISTANCE in paused.completion().ruleProblems(cfg,UploadFixture.now.epochSecond))
    }
    @Test fun `subsecond finish records a local time failure without uploading or declaring platform invalid`()=runBlocking {
        val h=Harness();assertTrue(h.controller.start());h.clock+=100
        assertFalse(h.controller.finish());assertEquals("MATERIAL_TIME",h.controller.summary.problem)
        assertEquals(0,h.uploads);assertFalse(h.controller.summary.recordVerified)
        assertEquals(RunMaterialProblem.TIME,h.ledger.materialProblem())
    }
    @Test fun `failed location response cannot acknowledge points or trigger catch up writes`()=runBlocking {
        val h=Harness();assertTrue(h.controller.start());h.fail=RunEndpoint.LOCATION;h.clock+=20_000;h.controller.tick()
        assertEquals(0,h.controller.summary.acknowledgedPoints)
        repeat(3) { h.clock+=20_000;h.controller.tick() }
        assertEquals(1,h.requests.count { it==RunEndpoint.LOCATION });assertEquals(0,h.uploads)
    }
    @Test fun `partial upload failure fences finish and does not reupload or associate`()=runBlocking {
        val h=Harness();assertTrue(h.controller.start());h.failUploadAt=2;h.clock+=40_000;h.controller.tick()
        repeat(3) { h.controller.tick();assertFalse(h.controller.finish()) }
        assertEquals(2,h.uploads);assertEquals(0,h.requests.count { it==RunEndpoint.FINISH || it==RunEndpoint.GYROSCOPE })
        assertTrue(h.ledger.summary.needsReconciliation)
    }
    @Test fun `late finish response after background binds receipt but does not associate or query`()=runBlocking {
        val h=Harness();assertTrue(h.controller.start())
        h.during={ if(it==RunEndpoint.FINISH)h.controller.interrupt() };h.clock+=40_000;h.controller.tick()
        assertTrue(h.controller.summary.recordBound);assertFalse(h.controller.summary.recordVerified)
        assertEquals(0,h.requests.count { it==RunEndpoint.GYROSCOPE || it==RunEndpoint.RECORD })
    }
    @Test fun `concurrent start tick and finish cannot queue duplicate operations behind registration`()=runBlocking {
        val h=Harness()
        val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        h.during={ if(it==RunEndpoint.REGISTER) { entered.countDown();check(release.await(5,java.util.concurrent.TimeUnit.SECONDS)) } }
        val first=kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).async { h.controller.start() }
        try {
            assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(h.controller.start());h.controller.tick();assertFalse(h.controller.finish())
        } finally { release.countDown() }
        assertTrue(first.await());assertEquals(1,h.requests.count { it==RunEndpoint.REGISTER })
        assertEquals(0,h.uploads)
    }
    @Test fun `whole second protocol duration cannot round a short automatic run faster than its target pace`() {
        val short=route().copy(legs=listOf(ObservedRoadEdge("origin","21",listOf(p(-10.0),p(0.0)),10.0),
            ObservedRoadEdge("21","22",listOf(p(0.0),p(0.1)),0.1)),minimumTargetMeters=5.0)
        val run=AutomaticRouteRun(short,mapOf("21" to p(0.0),"22" to p(0.1)),1.0,metric,200)
        assertEquals(3000L,run.durationMillis)
        assertTrue(run.durationMillis/run.totalMeters>=200)
    }

}
