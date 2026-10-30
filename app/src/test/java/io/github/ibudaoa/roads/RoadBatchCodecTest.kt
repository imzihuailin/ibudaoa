package io.github.ibudaoa.roads

import io.github.ibudaoa.diagnostics.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RoadBatchCodecTest {
    private fun report() = RoadBatchReport(RoadBatchPlan.create(RoadFixture.config(), 1).scope,
        RoadBatchPhase.INTERRUPTED, 3, 2, listOf(RoadLinkSummary("a", "b", distanceMeters = 123.45,
            startOffsetMeters = 0.0, endOffsetMeters = 1.0, leavesBoundary = false, forbiddenUnknown = false)))

    @Test fun `safe summary roundtrips without geometry or SDK free text`() {
        val text = RoadBatchCodec.text(report())
        assertEquals(report(), RoadBatchCodec.decode(text))
        assertFalse(text.contains("latitude")); assertFalse(text.contains("polyline"))
        assertFalse(text.contains("road_name")); assertFalse(text.contains("http"))
    }

    @Test fun `invalid numbers unsafe identities impossible completion and future versions fail closed`() {
        listOf(report().copy(phase = RoadBatchPhase.COMPLETE),
            report().copy(links = listOf(report().links.single().copy(fromId = "https://private.invalid"))),
            report().copy(links = listOf(report().links.single().copy(distanceMeters = Double.NaN))),
            report().copy(links = listOf(report().links.single().copy(distanceMeters = -1.0))))
            .forEach { assertTrue(runCatching { RoadBatchCodec.text(it) }.isFailure) }
        assertTrue(runCatching { RoadBatchCodec.decode(RoadBatchCodec.text(report()).replace("\"schema\": 1", "\"schema\": 2")) }.isFailure)
    }

    @Test fun `combined export includes only same-scope road report and stays import compatible`() {
        val config = RoadFixture.config()
        val text = PreparationExport.encode(config, 1, 1, report())
        val root = Json.parseToJsonElement(text) as JsonObject
        assertTrue(root.containsKey("preparation_check"))
        assertEquals(config.config, DiagnosticCodec.decode(text).config)
        assertFalse((Json.parseToJsonElement(PreparationExport.encode(config, 2, 1, report())) as JsonObject)
            .containsKey("preparation_check"))
        assertFalse((Json.parseToJsonElement(PreparationExport.encode(RoadFixture.config(zone = 4), 1, 1, report())) as JsonObject)
            .containsKey("preparation_check"))
    }

    @Test fun `historical report remains revision one and never upgrades missing observations`() {
        val objectWithoutRevision = JsonObject(RoadBatchCodec.encode(report()).filterKeys { it != "review_revision" })
        val old = RoadBatchCodec.decode(objectWithoutRevision.toString())
        assertEquals(1, old.reviewRevision)
        assertEquals(1, (RoadBatchCodec.encode(old).getValue("review_revision") as JsonPrimitive).int)
    }

    @Test fun `unknown fields in stored summary are never copied into a new export`() {
        val raw = RoadBatchCodec.encode(report())
        val withExtra = JsonObject(raw + ("token" to JsonPrimitive("do-not-copy")))
        val safe = RoadBatchCodec.text(RoadBatchCodec.decode(withExtra.toString()))
        assertFalse(safe.contains("do-not-copy")); assertFalse(safe.contains("token"))
    }
}
