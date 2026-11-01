package io.github.ibudaoa.running

import io.github.ibudaoa.diagnostics.*
import io.github.ibudaoa.domain.*
import io.github.ibudaoa.maps.*
import io.github.ibudaoa.preview.*
import io.github.ibudaoa.roads.*
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RunningPlanTest {
    internal val config=DiagnosticCodec.decode("""{"school_id":1,"term_id":2,"run_zone_id":3,
      "role":1,"authStatus":2,"set_point_type":1,"point_type":1,"repeat_num":0,"log_mode":1,"log_logic":1,"is_limit_range":0,
      "term_start_time":1767225600,"term_end_time":1798761600,"run_face_verify_config":{},
      "run_start_time":21600,"run_end_time":79200,"week_time":[0,1,2,3,4,5,6],"dayrule_ids":"","run_tip_type":0,
      "time_rule_arr":[{"start_time":21600,"end_time":79200,"min_distance":0.3,"max_distance":1,
        "max_pace":180,"min_pace":540,"min_log_num":2,"point_num_online":2,"point_num_offline":0}],
      "run_zone_latlng":["113.99,29.99","114.01,29.99","114.01,30.01","113.99,30.01"],"run_zone_latlng_invalid":[],
      "run_line_info":{"point_min_distance":0.01,"point_max_distance1":1,"point_max_distance2":1,"point_update_distance":0.5,"point_list":[
        {"id":"p0","jingwei":"30.0,114.0","type":2,"point_type":1,"is_online":1,"is_del":0,"ctrl_status":1},
        {"id":"p1","jingwei":"30.001,114.0","type":2,"point_type":1,"is_online":1,"is_del":0,"ctrl_status":1},
        {"id":"p2","jingwei":"30.002,114.0","type":2,"point_type":1,"is_online":1,"is_del":0,"ctrl_status":1}]}}""")
    private val zone=ZoneId.of("Asia/Shanghai")
    private val now=Instant.parse("2026-10-28T00:20:00Z")
    private fun changed(key:String,value:JsonElement)=config.copy(config=JsonObject(config.config+mapOf(key to value)))
    private fun draft(cfg:DiagnosticConfig=config):FixedPointRoute {
        val metric=AmapDistance();val nodes=ConfigurationReviewer.review(cfg).candidates!!.associate { it.id to it.coordinate }
        val scope=CandidateRoadRequest.create(cfg,1,"p0","p1").scope
        fun edge(a:String,b:String)=ObservedRoadEdge(a,b,listOf(nodes.getValue(a),nodes.getValue(b)),metric.meters(nodes.getValue(a),nodes.getValue(b)))
        val graph=ObservedRoadGraph(scope,"a".repeat(64),nodes,listOf(edge("p0","p1"),edge("p1","p0"),edge("p1","p2"),edge("p2","p1")),emptySet(),emptySet(),reviewedTwoWayRoadCount=2)
        val fix=DisplayFix(CampusCoordinate(30.0005,114.00001),3f,1000)
        val pending=(LocalRoutePreviewEngine.prepare(cfg,true,graph,fix,null,now,1001,zone,metric) as PreviewPreparation.Ready).preview
        return LocalRoutePreviewEngine.confirm(cfg,true,graph,pending,fix,now,1002,zone,metric).first!!.route!!
    }
    @Test fun `comparison uses persisted structure projection for nested arrays`() {
        val saved=io.github.ibudaoa.diagnostics.DiagnosticCodec.decode("""{"school_id":1,"run_zone_id":3,"run_face_verify_config":{}}""")
        val fresh=saved.copy(structure=io.github.ibudaoa.diagnostics.ConfigStructure(mapOf("run_zone_latlng[][]" to "number"),emptyList()))
        val persisted=RunningRoadSnapshot.canonical(fresh)
        assertTrue(OnlineConfigurationCompatibility.compatible(persisted,fresh))
        assertFalse(OnlineConfigurationCompatibility.compatible(persisted,fresh.copy(config=kotlinx.serialization.json.JsonObject(fresh.config+("run_zone_id" to kotlinx.serialization.json.JsonPrimitive(4))))))
    }
    @Test fun `full local route produces dynamic fast side pace and sufficient duration margin`() {
        val route=draft();val (plan,summary)=RunningPlanRules.create(config,route,now,zone)
        assertNotNull(plan);assertEquals(RunningPlanPhase.PLANNED,summary.phase)
        assertEquals(198,plan!!.targetPaceSecondsPerKm)
        assertTrue(plan.durationSeconds*1000.0/route.upperDistanceMeters>180)
        assertTrue(plan.durationSeconds*1000.0/route.lowerDistanceMeters<540)
        assertEquals(2,summary.pointCount)
        assertTrue(summary.distanceMeters!!>=300)
    }
    @Test fun `Sunday zero mapping and explicit school timezone are honored`() {
        val sunday=Instant.parse("2026-11-01T00:20:00Z")
        val onlySunday=changed("week_time",JsonArray(listOf(JsonPrimitive(0))))
        assertEquals(WeekPosition.ALLOWED,CalendarReviewer.review(onlySunday,sunday,zone).weekPosition)
        assertEquals(WeekPosition.BLOCKED,CalendarReviewer.review(onlySunday,now,zone).weekPosition)
        assertEquals(RunningPlanProblem.WEEK,RunningPlanRules.problem(onlySunday,now,zone))
    }
    @Test fun `unknown and nonempty date rule sets never become an implicit unrestricted day`() {
        assertEquals(RunningPlanProblem.DATE_RULE,RunningPlanRules.problem(changed("dayrule_ids",JsonPrimitive("1,2")),now,zone))
        val missing=config.copy(config=JsonObject(config.config-"dayrule_ids"))
        assertEquals(RunningPlanProblem.DATE_RULE,RunningPlanRules.problem(missing,now,zone))
    }
    @Test fun `broad time window also applies even if a specific time rule matches`() {
        assertEquals(RunningPlanProblem.TIME,RunningPlanRules.problem(changed("run_start_time",JsonPrimitive(40000)),now,zone))
        assertEquals(BroadTimePosition.UNKNOWN,CalendarReviewer.review(changed("run_end_time",JsonPrimitive(-1)),now,zone).broadTimePosition)
    }
    @Test fun `server restrictions and nonempty face configuration stop planning`() {
        assertEquals(RunningPlanProblem.PLATFORM_RESTRICTION,RunningPlanRules.problem(changed("run_tip_type",JsonPrimitive(4)),now,zone))
        assertEquals(RunningPlanProblem.FACE,RunningPlanRules.problem(changed("run_face_verify_config",buildJsonObject { put("start_status",1) }),now,zone))
    }
    @Test fun `term and closing time do not permit an impossible finishing schedule`() {
        assertEquals(RunningPlanProblem.TERM,RunningPlanRules.problem(config,Instant.parse("2027-03-01T00:20:00Z"),zone))
        val late=Instant.parse("2026-10-28T13:59:55Z")
        assertEquals(RunningPlanProblem.TIME_REMAINING,RunningPlanRules.create(config,draft(),late,zone).second.problem)
    }
    @Test fun `changed rule pace is derived anew rather than fixed to three minutes`() {
        val rule=config.config.getValue("time_rule_arr").jsonArray.single().jsonObject
        val cfg=changed("time_rule_arr",JsonArray(listOf(JsonObject(rule+mapOf("max_pace" to JsonPrimitive(240),"min_pace" to JsonPrimitive(600))))))
        val (plan,_)=RunningPlanRules.create(cfg,draft(cfg),now,zone)
        assertEquals(258,plan!!.targetPaceSecondsPerKm)
    }
    @Test fun `unreviewed incomplete or wrong scope routes cannot be promoted to a plan`() {
        val route=draft()
        for(bad in listOf(route.copy(includesReviewedOrigin=false),route.copy(legs=emptyList()),route.copy(selectedIds=listOf("p0")),route.copy(scope=route.scope.copy(zoneId=999))))
            assertEquals(RunningPlanProblem.ROADS,RunningPlanRules.create(config,bad,now,zone).second.problem)
    }
    @Test fun `online rule and counter changes preserve geometry but new zone point table or checks do not`() {
        assertTrue(OnlineConfigurationCompatibility.compatible(config,changed("run_log_num",JsonPrimitive(2))))
        assertTrue(OnlineConfigurationCompatibility.compatible(config,changed("time_rule_arr",JsonArray(emptyList()))))
        assertFalse(OnlineConfigurationCompatibility.compatible(config,changed("run_zone_id",JsonPrimitive(4))))
        assertFalse(OnlineConfigurationCompatibility.compatible(config,changed("is_check_shumeng",JsonPrimitive(1))))
        assertFalse(OnlineConfigurationCompatibility.compatible(config,changed("run_line_info",JsonObject(emptyMap()))))
    }
    @Test fun `daily response metadata shapes never invalidate persisted campus roads`() {
        val old=config.copy(structure=io.github.ibudaoa.diagnostics.ConfigStructure(mapOf(
            "run_notes" to "array", "run_notes[]" to "string", "sports_data" to "array",
            "weekData.list" to "array", "run_line_info.point_list[].cover_url" to "string",
            "is_check_shumeng" to "number"),emptyList()))
        val fresh=changed("run_log_num",JsonPrimitive(2)).copy(structure=io.github.ibudaoa.diagnostics.ConfigStructure(mapOf(
            "run_notes" to "array", "sports_data" to "object", "sports_data.distance" to "number",
            "run_line_info.point_list[].cover_url" to "null", "is_check_shumeng" to "number"),emptyList()))
        assertTrue(OnlineConfigurationCompatibility.compatible(old,fresh))
        assertFalse(OnlineConfigurationCompatibility.compatible(old,fresh.copy(structure=fresh.structure!!.copy(
            fieldTypes=fresh.structure!!.fieldTypes+("is_check_shumeng" to "object")))))
    }
    @Test fun `school metadata update does not authorize zone point or verification changes`() {
        val fresh=changed("school_auth_num",JsonPrimitive(202))
        assertTrue(OnlineConfigurationCompatibility.compatible(config,fresh))
        for(key in listOf("school_id","term_id","run_zone_id","run_line_info","run_zone_latlng","is_check_face","authStatus")) {
            val unsafe=fresh.copy(config=JsonObject(fresh.config+mapOf(key to JsonPrimitive(999))))
            assertFalse(key,OnlineConfigurationCompatibility.compatible(config,unsafe))
        }
    }
    @Test fun `start recheck refuses closing time and next day without changing the prepared route`() {
        val plan=RunningPlanRules.create(config,draft(),now,zone).first!!
        assertNull(RunningPlanRules.recheck(config,plan,now.plusSeconds(10),zone))
        assertEquals(RunningPlanProblem.TIME_REMAINING,RunningPlanRules.recheck(config,plan,Instant.parse("2026-10-28T13:59:55Z"),zone))
        assertNotNull(RunningPlanRules.recheck(config,plan,now.plusSeconds(86400),zone))
    }
    @Test fun `start recheck rejects changed pace even when the route still meets distance limits`() {
        val plan=RunningPlanRules.create(config,draft(),now,zone).first!!
        val rule=config.config.getValue("time_rule_arr").jsonArray.single().jsonObject
        val cfg=changed("time_rule_arr",JsonArray(listOf(JsonObject(rule+("max_pace" to JsonPrimitive(240))))))
        assertNotNull(RunningPlanRules.recheck(cfg,plan,now,zone))
    }
    @Test fun `plan exports only aggregate values and importing it grants no plan or motion`() {
        val summary=RunningPlanRules.create(config,draft(),now,zone).second
        val text=PreparationExport.encode(config,1,1,null,runningPlan=summary)
        val root=Json.parseToJsonElement(text).jsonObject;val report=root.getValue("running_plan").jsonObject
        assertEquals("PLANNED",report.getValue("phase").jsonPrimitive.content)
        assertFalse(report.getValue("platform_started").jsonPrimitive.boolean)
        assertFalse(report.getValue("record_verified").jsonPrimitive.boolean)
        assertTrue(root.containsKey("app_version"))
        assertEquals(setOf("phase","was_generated","distance_meters","pace_seconds_per_km","duration_seconds","point_count","access_distance_meters","platform_started","record_verified"),report.keys)
        assertFalse(DiagnosticCodec.encode(DiagnosticCodec.decode(text)).contains("running_plan"))
    }
}
