package io.github.ibudaoa.diagnostics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticCodecTest {
    private fun decode(fields: String = "") = DiagnosticCodec.decode(
        """{"school_id":11,"run_zone_id":433${if (fields.isEmpty()) "" else ",$fields"}}""",
    )

    @Test fun decorated_date_labels_preserve_only_valid_date_literals() {
        val config = decode(""""term_date":"<b>学期（2026年10月26日 至 2027/01/08）</b> https://private.invalid/?token=PRIVATE","week_time":["0","6"]""")
        val text = DiagnosticCodec.encode(config)
        assertTrue(text.contains("2026-10-26")); assertTrue(text.contains("2027-01-08"))
        assertFalse(text.contains("PRIVATE")); assertFalse(text.contains("private.invalid")); assertFalse(text.contains("<b>"))
        assertEquals(config.config, DiagnosticCodec.decode(text).config)
        val invalid = decode(""""term_date":"2026-02-30"""")
        assertNull(invalid.config["term_date_evidence"])
    }

    @Test fun `official data envelope and numeric string ids are supported`() {
        val config = DiagnosticCodec.decode("""{"data":{"school_id":"11","run_zone_id":"433","school_name":"测试大学"}}""")
        assertEquals(433L, config.zoneId)
        assertEquals("测试大学", config.schoolName)
    }

    @Test fun `missing face fields remain unknown`() {
        assertEquals(StageVerification.UNKNOWN, decode().stageVerification)
        assertEquals(StageVerification.UNKNOWN, decode(""""run_face_verify_config":{"start_status":0,"middle_status":0}""").stageVerification)
    }

    @Test fun `face registration does not imply face requirement`() {
        assertEquals(StageVerification.DISABLED, decode(""""is_register_face":0,"run_face_verify_config":{"start_status":0,"middle_status":0,"stop_status":0}""").stageVerification)
        assertEquals(StageVerification.UNKNOWN, decode(""""is_register_face":1""").stageVerification)
    }

    @Test fun `explicit face requirement is retained`() {
        assertEquals(StageVerification.REQUIRED, decode(""""run_face_verify_config":{"start_status":0,"middle_status":1,"stop_status":0}""").stageVerification)
        assertEquals(StageVerification.UNKNOWN, decode(""""run_face_verify_config":{"start_status":0,"middle_status":2,"stop_status":0}""").stageVerification)
    }

    @Test fun `credentials personal data and unknown fields never survive projection`() {
        val config = decode("""
            "access_token":"PRIVATE_TOKEN_SENTINEL",
            "uid":99887766,
            "student_num":"PRIVATE_STUDENT_SENTINEL",
            "user_avatar":"https://private.invalid/image",
            "unknown":{"nested":"PRIVATE_NESTED_SENTINEL"},
            "run_line_info":{"uid":"PRIVATE_LINE_SENTINEL","point_list":[{"id":5,"latitude":30.5,"longitude":114.4,"token":"PRIVATE_POINT_SENTINEL"}]},
            "time_rule_arr":[{"min_distance":2,"token":"PRIVATE_RULE_SENTINEL"}],
            "run_face_verify_config":{"start_status":0,"middle_status":0,"stop_status":0,"face_img":"PRIVATE_FACE_SENTINEL"}
        """.trimIndent())
        val exported = DiagnosticCodec.encode(config)
        assertFalse(exported.contains("PRIVATE_"))
        assertFalse(exported.contains("private.invalid"))
        assertFalse(exported.contains("99887766"))
        assertEquals(1, config.candidateCount)
        assertEquals(1, config.ruleCount)
    }

    @Test fun `numeric slots cannot carry arbitrary strings or nested credentials`() {
        val config = decode(""""min_pace":"PRIVATE_VALUE","point_num":{"token":"PRIVATE_OBJECT"},"min_distance":true""")
        assertFalse(DiagnosticCodec.encode(config).contains("PRIVATE"))
        assertNull(config.config["min_distance"])
        assertNull(config.config["point_num"])
    }

    @Test fun `export reprojects manually constructed configuration`() {
        val unsafe = DiagnosticConfig(Json.parseToJsonElement("""{"school_id":11,"run_zone_id":433,"token":"PRIVATE_CONSTRUCTED"}""").jsonObject)
        assertFalse(DiagnosticCodec.encode(unsafe).contains("PRIVATE_CONSTRUCTED"))
    }

    @Test fun `url like labels and region strings are removed or masked`() {
        val config = decode(""""school_name":"https://private.invalid/auth?token=secret","run_zone_latlng":["114.4,30.5","https://private.invalid/region"]""")
        val exported = DiagnosticCodec.encode(config)
        assertEquals("[已隐藏]", config.schoolName)
        assertFalse(exported.contains("private.invalid"))
        assertTrue(exported.contains("114.4,30.5"))
    }

    @Test fun `sanitized export can be imported without losing configuration`() {
        val config = decode(""""school_name":"测试大学","time_rule_arr":[{"start_time":1,"end_time":2}]""")
        assertEquals(config, DiagnosticCodec.decode(DiagnosticCodec.encode(config)))
    }

    @Test fun `missing or invalid identities fail without echoing input`() {
        expectError(DiagnosticError.NOT_CONFIG) { DiagnosticCodec.decode("""{"token":"SECRET"}""") }
        expectError(DiagnosticError.NOT_CONFIG) { DiagnosticCodec.decode("""{"school_id":11,"run_zone_id":0}""") }
        expectError(DiagnosticError.NOT_CONFIG) { DiagnosticCodec.decode("""{"school_id":11,"run_zone_id":"SECRET"}""") }
    }

    @Test fun `oversized malformed and excessively nested files fail predictably`() {
        expectError(DiagnosticError.TOO_LARGE) { DiagnosticCodec.decode(" ".repeat(DiagnosticCodec.MAX_BYTES + 1)) }
        expectError(DiagnosticError.INVALID_JSON) { DiagnosticCodec.decode("PRIVATE_MALFORMED_INPUT") }
        expectError(DiagnosticError.TOO_DEEP) { DiagnosticCodec.decode("[".repeat(33) + "]".repeat(33)) }
    }

    @Test fun `braces in quoted text do not count towards nesting`() {
        val config = decode(""""school_name":"${"[".repeat(40)}""" + '"')
        assertEquals(433L, config.zoneId)
    }

    @Test fun `new campus snapshot cannot retain old candidate data`() {
        val old = decode(""""run_line_info":{"point_list":[{"id":1}]}""")
        val new = DiagnosticCodec.decode("""{"school_id":11,"run_zone_id":434}""")
        assertEquals(1, old.candidateCount)
        assertEquals(0, new.candidateCount)
        assertEquals(434L, new.zoneId)
    }

    @Test fun `official jingwei and exact point ids survive without including photo urls`() {
        val config = decode(""""run_line_info":{"point_list":[{"id":"00017","jingwei":"114.123456,30.123456","point_type":1,"address":"测试教学楼","cover_url":"https://private.invalid/image"}]}""")
        val exported = DiagnosticCodec.encode(config)
        assertTrue(exported.contains("00017"))
        assertTrue(exported.contains("114.123456,30.123456"))
        assertTrue(exported.contains("测试教学楼"))
        assertFalse(exported.contains("private.invalid"))
        assertEquals(config, DiagnosticCodec.decode(exported))
    }

    @Test fun `invalid coordinate strings cannot hide arbitrary payloads`() {
        val config = decode(""""run_line_info":{"point_list":[{"id":1,"jingwei":"PRIVATE_COORDINATE"},{"id":2,"jingwei":"999,999"}]}""")
        val exported = DiagnosticCodec.encode(config)
        assertFalse(exported.contains("PRIVATE_COORDINATE"))
        assertFalse(exported.contains("jingwei"))
    }

    @Test fun `weekday date and boolean rule fields survive without inventing defaults`() {
        val config = decode(""""week_time":["0","2","6"],"robot_module":true,"max_num":1,"term_date":"2026-10-26 至 2027-01-08","dayrule_ids":"17,18","running_time":[{"start_time":"21600","end_time":"79200","time_type":"1"}]""")
        assertTrue(config.config.containsKey("week_time"))
        assertTrue(config.config.containsKey("robot_module"))
        assertTrue(config.config.containsKey("term_date"))
        assertTrue(config.config.containsKey("running_time"))
        assertEquals(config, DiagnosticCodec.decode(DiagnosticCodec.encode(config)))
        assertNull(decode().config["robot_module"])
        assertNull(decode().config["week_time"])
    }

    @Test fun `invalid weekday restriction cannot silently become an empty unrestricted list`() {
        val config = decode(""""week_time":["0","99999999"]""")
        assertNull(config.config["week_time"])
        assertFalse(DiagnosticCodec.encode(config).contains("99999999"))
    }

    @Test fun `additional config fields cannot preserve arbitrary urls or photos`() {
        val config = decode(""""dayrule_ids":"PRIVATE_RULE_IDS","term_date":"https://private.invalid/auth","capture_config":{"capture_type":0,"face_img":"PRIVATE_PHOTO"}""")
        val exported = DiagnosticCodec.encode(config)
        assertFalse(exported.contains("PRIVATE_"))
        assertFalse(exported.contains("private.invalid"))
        assertTrue(exported.contains("capture_type"))
    }

    @Test fun `empty face object is temporarily skipped without inventing disabled flags`() {
        val config = decode(""""run_face_verify_config":{}""")
        assertEquals(StageVerification.SKIPPED_EMPTY, config.stageVerification)
        assertEquals(FaceConfigShape.EMPTY_OBJECT, config.faceConfigShape)
        assertEquals("{}", config.config["run_face_verify_config"].toString())
        assertEquals(config, DiagnosticCodec.decode(DiagnosticCodec.encode(config)))
    }

    @Test fun `null missing and malformed face values are not empty objects`() {
        assertEquals(StageVerification.UNKNOWN, decode().stageVerification)
        listOf("null", "[]", "\"\"", "0").forEach { value ->
            val config = decode("\"run_face_verify_config\":$value")
            assertEquals(StageVerification.UNKNOWN, config.stageVerification)
            assertEquals(config, DiagnosticCodec.decode(DiagnosticCodec.encode(config)))
        }
    }

    @Test fun `projection cannot turn unsupported nonempty face configuration into a skipped object`() {
        listOf("""{"future_required":1}""", """{"start_status":"PRIVATE_BAD_VALUE"}""",
            """{"start_status":0,"middle_status":0,"stop_status":0,"face_verify_type":"PRIVATE_BAD_VALUE"}""",
            """{"start_status":0,"middle_status":0,"stop_status":0,"future_required":1}""").forEach { face ->
            val config = decode("\"run_face_verify_config\":$face")
            assertEquals(StageVerification.UNKNOWN, config.stageVerification)
            val exported = DiagnosticCodec.encode(config)
            assertFalse(exported.contains("PRIVATE_BAD_VALUE"))
            assertEquals(StageVerification.UNKNOWN, DiagnosticCodec.decode(exported).stageVerification)
        }
    }

    @Test fun `explicit future requirement is never skipped even with contradictory metadata`() {
        val data = """{"format":"${DiagnosticCodec.FORMAT}","face_config_shape":"EMPTY_OBJECT","config":{"school_id":11,"run_zone_id":433,"run_face_verify_config":{"start_status":1}}}"""
        assertEquals(StageVerification.REQUIRED, DiagnosticCodec.decode(data).stageVerification)
        val unsupported = decode(""""run_face_verify_config":{"start_status":1,"future_required":1}""")
        assertEquals(StageVerification.REQUIRED, unsupported.stageVerification)
    }

    @Test fun `legacy structure evidence prevents lossy nonempty face export from being skipped`() {
        val data = """{"format":"${DiagnosticCodec.FORMAT}","config":{"school_id":11,"run_zone_id":433,"run_face_verify_config":{}},"structure":{"field_types":{"run_face_verify_config":"object","run_face_verify_config.future_required":"number"},"missing_sections":[]}}"""
        assertEquals(StageVerification.UNKNOWN, DiagnosticCodec.decode(data).stageVerification)
    }

    @Test fun `new requirement is assessed again rather than inheriting earlier empty skip`() {
        assertEquals(StageVerification.SKIPPED_EMPTY, decode(""""run_face_verify_config":{}""").stageVerification)
        assertEquals(StageVerification.REQUIRED, decode(""""run_face_verify_config":{"stop_status":1}""").stageVerification)
        assertEquals(StageVerification.SKIPPED_EMPTY, decode(""""run_face_verify_config":{}""").stageVerification)
    }

    @Test fun `future or malformed shape metadata is not silently accepted as empty`() {
        listOf("\"FUTURE_SHAPE\"", "null", "{}").forEach { metadata ->
            val data = """{"format":"${DiagnosticCodec.FORMAT}","face_config_shape":$metadata,"config":{"school_id":11,"run_zone_id":433,"run_face_verify_config":{}}}"""
            assertEquals(StageVerification.UNKNOWN, DiagnosticCodec.decode(data).stageVerification)
        }
    }

    private fun expectError(reason: DiagnosticError, action: () -> Unit) {
        val result = runCatching(action).exceptionOrNull()
        assertTrue(result is DiagnosticException)
        assertEquals(reason, (result as DiagnosticException).reason)
        assertFalse(result.message.orEmpty().contains("SECRET"))
        assertFalse(result.message.orEmpty().contains("PRIVATE"))
    }
}
