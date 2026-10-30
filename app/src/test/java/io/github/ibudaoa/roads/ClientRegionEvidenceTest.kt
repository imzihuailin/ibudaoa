package io.github.ibudaoa.roads

import io.github.ibudaoa.diagnostics.DiagnosticCodec
import org.junit.Assert.assertEquals
import org.junit.Test

class ClientRegionEvidenceTest {
    private val source = """{"school_id":1,"run_zone_id":2,"role":1,"authStatus":2,"log_mode":1,
        "point_type":1,"is_limit_range":0,"log_logic":1}"""
    @Test fun `only the observed student branch is classified as client fence disabled`() {
        assertEquals(RouteRegionMode.CLIENT_FENCE_DISABLED, ClientRegionEvidence.forCurrentClient(DiagnosticCodec.decode(source)))
        listOf("\"role\":1" to "\"role\":2", "\"authStatus\":2" to "\"authStatus\":1",
            "\"log_mode\":1" to "\"log_mode\":2", "\"point_type\":1" to "\"point_type\":2",
            "\"is_limit_range\":0" to "\"is_limit_range\":1", "\"log_logic\":1" to "\"log_logic\":3",
            "\"is_limit_range\":0" to "\"is_limit_range\":null").forEach { (old, new) ->
            assertEquals(RouteRegionMode.UNKNOWN, ClientRegionEvidence.forCurrentClient(DiagnosticCodec.decode(source.replace(old,new))))
        }
    }
}
