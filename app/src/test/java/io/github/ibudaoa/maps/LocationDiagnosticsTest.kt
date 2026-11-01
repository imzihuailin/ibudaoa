package io.github.ibudaoa.maps

import io.github.ibudaoa.diagnostics.DiagnosticCodec
import io.github.ibudaoa.diagnostics.PreparationExport
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LocationDiagnosticsTest {
    @Test fun `automatic restart retains concrete failure reasons until a valid update`() {
        val health=LocationHealthTracker(true,true)
        health.sdkError(4);health.restarted(retainFailure=true);health.expired()
        assertEquals(LocationCheckStatus.SDK_ERROR,health.value.status)
        assertEquals(4,health.value.lastSdkCode);assertEquals(1,health.value.retries)
        health.sample(LocationCoordinateFrame.GCJ02,60f,FixRejection.ACCURACY)
        health.restarted(retainFailure=true)
        assertEquals(LocationCheckStatus.REJECTED,health.value.status)
        assertEquals(FixRejection.ACCURACY,health.value.lastRejection)
        health.sample(LocationCoordinateFrame.GCJ02,8f,null)
        assertEquals(LocationCheckStatus.ACCEPTED,health.value.status);assertNull(health.value.lastRejection)
    }
    @Test fun `a retained position can expire during an automatic restart without erasing later SDK errors`() {
        val health=LocationHealthTracker(true,true)
        health.sample(LocationCoordinateFrame.GCJ02,8f,null);health.restarted(retainFailure=true)
        health.expired();assertEquals(LocationCheckStatus.EXPIRED,health.value.status)
        health.sdkError(13);health.expired();assertEquals(LocationCheckStatus.SDK_ERROR,health.value.status)
    }
    @Test fun `waiting and expiry cannot erase an SDK error`() {
        val health=LocationHealthTracker(true,true)
        health.sdkError(4)
        health.noCallback();health.expired()
        assertEquals(LocationCheckStatus.SDK_ERROR,health.value.status)
        assertEquals(4,health.value.lastSdkCode)
        assertEquals(1,health.value.callbacks)
        assertEquals(0,health.value.accepted)
        assertEquals("定位网络连接失败（4）",health.value.label)
    }
    @Test fun `a silent SDK becomes explicit and can recover on its first callback`() {
        val health=LocationHealthTracker(true,true)
        health.noCallback()
        assertEquals(LocationCheckStatus.NO_CALLBACK,health.value.status)
        health.sample(LocationCoordinateFrame.GCJ02,8f,null)
        assertEquals(LocationCheckStatus.ACCEPTED,health.value.status)
        assertEquals(1,health.value.accepted)
        health.expired();health.noCallback()
        assertEquals(LocationCheckStatus.EXPIRED,health.value.status)
        assertEquals(1,health.value.accepted)
    }
    @Test fun `rejection reason remains distinct from precision and recovers on a valid sample`() {
        val health=LocationHealthTracker(true,true)
        health.sample(LocationCoordinateFrame.WGS84,8f,FixRejection.WRONG_FRAME)
        health.noCallback();health.expired()
        assertEquals(FixRejection.WRONG_FRAME,health.value.lastRejection)
        assertEquals(LocationAccuracyBand.UP_TO_10M,health.value.accuracyBand)
        assertEquals(0,health.value.accepted)
        health.sample(LocationCoordinateFrame.GCJ02,25f,null)
        assertEquals(2,health.value.callbacks)
        assertEquals(1,health.value.accepted)
        assertNull(health.value.lastRejection)
    }
    @Test fun `permission disabled system and initialization failure are not generic waits`() {
        val permission=LocationHealthTracker(false,true);permission.noCallback()
        assertEquals(LocationCheckStatus.PERMISSION_REQUIRED,permission.value.status)
        val disabled=LocationHealthTracker(true,false);disabled.noCallback()
        assertEquals(LocationCheckStatus.SYSTEM_LOCATION_OFF,disabled.value.status)
        val failed=LocationHealthTracker(true,null);failed.initializationFailed();failed.noCallback()
        assertEquals(LocationCheckStatus.INITIALIZATION_FAILED,failed.value.status)
    }
    @Test fun `diagnostic export is only an allowlist of counts enums and permission state`() {
        val config=DiagnosticCodec.decode("""{"school_id":1,"term_id":2,"run_zone_id":3}""")
        val health=LocationHealthTracker(true,true)
        health.sdkError(7)
        val text=PreparationExport.encode(config,1,1,null,location=health.value)
        val exported=Json.parseToJsonElement(text).jsonObject.getValue("foreground_location").jsonObject
        assertEquals(setOf("status","configured_transport","fine_permission","system_location_enabled","callbacks",
            "accepted","sdk_errors","retries","last_sdk_code","accuracy_band","coordinate_frame"),exported.keys)
        assertEquals(7,exported.getValue("last_sdk_code").jsonPrimitive.int)
        assertEquals("HTTPS",exported.getValue("configured_transport").jsonPrimitive.content)
        assertFalse(text.contains("latitude"));assertFalse(text.contains("longitude"));assertFalse(text.contains("errorInfo"))
        assertFalse(PreparationExport.encode(config,1,1,null,
            location=health.value.copy(accepted=10)).contains("foreground_location"))
    }
    @Test fun `unrecognized SDK codes and unsupported frames cannot export arbitrary strings`() {
        val health=LocationHealthTracker(true,null)
        health.sdkError(100_000)
        assertNull(health.value.lastSdkCode)
        health.sample(LocationCoordinateFrame.OTHER,Float.NaN,FixRejection.INVALID_COORDINATE)
        assertEquals(LocationAccuracyBand.UNKNOWN,health.value.accuracyBand)
        assertEquals(LocationCoordinateFrame.OTHER,health.value.coordinateFrame)
    }
}
