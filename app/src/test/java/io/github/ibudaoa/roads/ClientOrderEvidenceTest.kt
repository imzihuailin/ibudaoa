package io.github.ibudaoa.roads

import io.github.ibudaoa.diagnostics.DiagnosticCodec
import org.junit.Assert.assertEquals
import org.junit.Test

class ClientOrderEvidenceTest {
    private val source = """{"school_id":1,"run_zone_id":2,"role":1,"authStatus":2,"point_type":1,
        "set_point_type":1,"repeat_num":0,"log_mode":1}"""
    @Test fun `free ordering is limited to the observed client branch`() {
        assertEquals(RouteOrderMode.FREE, ClientOrderEvidence.forCurrentClient(DiagnosticCodec.decode(source)))
        listOf("\"log_mode\":1" to "\"log_mode\":2", "\"repeat_num\":0" to "\"repeat_num\":1",
            "\"point_type\":1" to "\"point_type\":2", "\"authStatus\":2" to "\"authStatus\":null")
            .forEach { (old,new) -> assertEquals(RouteOrderMode.UNKNOWN,
                ClientOrderEvidence.forCurrentClient(DiagnosticCodec.decode(source.replace(old,new)))) }
    }
}
