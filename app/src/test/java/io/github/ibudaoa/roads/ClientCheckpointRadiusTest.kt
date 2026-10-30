package io.github.ibudaoa.roads

import io.github.ibudaoa.diagnostics.DiagnosticCodec
import org.junit.Assert.*
import org.junit.Test

class ClientCheckpointRadiusTest {
    private fun config(whitelist: String = "0", radius: String = "100", mode: String = "1") = DiagnosticCodec.decode(
        """{"school_id":1,"term_id":2,"run_zone_id":3,"role":1,"authStatus":2,"point_type":1,"set_point_type":1,"repeat_num":0,
        "log_mode":$mode,"is_location_white_list":$whitelist,"run_line_info":{"log_max_distance":$radius}}"""
    )

    @Test fun `ordinary client uses configured distance`() {
        assertEquals(100.0, ClientCheckpointRadius.meters(config())!!, 0.0)
        assertEquals(75.0, ClientCheckpointRadius.meters(config(radius = "75"))!!, 0.0)
    }

    @Test fun `explicit location whitelist has client override`() {
        assertEquals(200.0, ClientCheckpointRadius.meters(config(whitelist = "1"))!!, 0.0)
    }

    @Test fun `unknown or unsupported configuration does not imply a radius`() {
        assertNull(ClientCheckpointRadius.meters(config(whitelist = "null")))
        assertNull(ClientCheckpointRadius.meters(config(radius = "0")))
        assertNull(ClientCheckpointRadius.meters(config(radius = "null")))
        assertNull(ClientCheckpointRadius.meters(config(mode = "2")))
    }
}
