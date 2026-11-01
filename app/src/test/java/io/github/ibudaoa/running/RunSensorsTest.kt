package io.github.ibudaoa.running

import io.github.ibudaoa.maps.DisplayFix
import io.github.ibudaoa.domain.CampusCoordinate
import io.github.ibudaoa.diagnostics.PreparationExport
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RunSensorsTest {
    private val start=1_000_000_000L
    private fun core(max:Int=20_000)=RunSensorCore(start,max).apply { hardware(true,false,true,true) }
    private fun fix(t:Long)=DisplayFix(CampusCoordinate(30.0,114.0),3f,t/1_000_000,1_700_000_000_000)
    private fun event(c:RunSensorCore,kind:RunSensorKind,t:Long,values:FloatArray=floatArrayOf(1f,2f,3f),position:DisplayFix?=fix(t)) =
        c.accept(kind,values,t,t,1_700_000_000_000,position)
    @Test fun `detector records real steps in their own minute and pause steps are excluded`() {
        val c=core()
        event(c,RunSensorKind.STEP_DETECTOR,start+1_000_000_000,floatArrayOf(1f))
        c.pause();event(c,RunSensorKind.STEP_DETECTOR,start+3_000_000_000,floatArrayOf(1f));c.resume()
        event(c,RunSensorKind.STEP_DETECTOR,start+61_000_000_000,floatArrayOf(1f))
        val m=c.measurements(70)
        assertEquals(2,m.stepCount);assertEquals(listOf(1,1),m.perMinuteSteps)
    }
    @Test fun `counter baselines and resets never invent boot steps or paused displacement`() {
        val c=core()
        event(c,RunSensorKind.STEP_COUNTER,start+1_000_000_000,floatArrayOf(5000f))
        event(c,RunSensorKind.STEP_COUNTER,start+2_000_000_000,floatArrayOf(5004f))
        c.pause();c.resume()
        event(c,RunSensorKind.STEP_COUNTER,start+3_000_000_000,floatArrayOf(5020f))
        event(c,RunSensorKind.STEP_COUNTER,start+4_000_000_000,floatArrayOf(5022f))
        event(c,RunSensorKind.STEP_COUNTER,start+5_000_000_000,floatArrayOf(1f))
        assertEquals(6,c.summary.steps)
        assertEquals(listOf(6),c.measurements(10).perMinuteSteps)
    }
    @Test fun `gyro and acceleration must both be fresh and are copied without interpolation`() {
        val c=core();val values=floatArrayOf(1f,2f,3f)
        event(c,RunSensorKind.ACCELEROMETER,start+100_000_000,values)
        values[0]=99f
        event(c,RunSensorKind.GYROSCOPE,start+120_000_000,floatArrayOf(4f,5f,6f))
        val measured=c.snapshot().single()
        assertEquals(listOf(1f,2f,3f),measured.acceleration)
        assertEquals(listOf(4f,5f,6f),measured.rotation)
        event(c,RunSensorKind.GYROSCOPE,start+140_000_000,floatArrayOf(7f,8f,9f))
        assertEquals(1,c.snapshot().size)
        assertFalse(c.summary.toString().contains("CampusCoordinate"))
        assertFalse(measured.toString().contains("170000"))
    }
    @Test fun `old future duplicate and malformed events cannot change steps or produce sensor samples`() {
        val c=core()
        c.accept(RunSensorKind.STEP_DETECTOR,floatArrayOf(1f),start-1,start+1,1700000000000,null)
        c.accept(RunSensorKind.STEP_DETECTOR,floatArrayOf(1f),start+100,start+1,1700000000000,null)
        event(c,RunSensorKind.STEP_DETECTOR,start+1_000_000_000,floatArrayOf(1f))
        event(c,RunSensorKind.STEP_DETECTOR,start+1_000_000_000,floatArrayOf(1f))
        event(c,RunSensorKind.STEP_DETECTOR,start+2_000_000_000,floatArrayOf(Float.NaN))
        event(c,RunSensorKind.STEP_DETECTOR,start+3_000_000_000,floatArrayOf(2f))
        assertEquals(1,c.summary.steps);assertTrue(c.summary.rejected>=5)
        event(c,RunSensorKind.ACCELEROMETER,start+4_000_000_000,position=null)
        event(c,RunSensorKind.GYROSCOPE,start+4_010_000_000,position=null)
        assertTrue(c.snapshot().isEmpty())
    }
    @Test fun `sensor sample capacity interrupts rather than truncating or inventing values`() {
        val c=core(1)
        for(t in listOf(start+100_000_000,start+400_000_000)) {
            event(c,RunSensorKind.ACCELEROMETER,t)
            event(c,RunSensorKind.GYROSCOPE,t+10_000_000)
        }
        assertEquals(1,c.snapshot().size);assertEquals(RunSensorPhase.INTERRUPTED,c.summary.phase)
        event(c,RunSensorKind.STEP_DETECTOR,start+1_000_000_000,floatArrayOf(1f))
        assertEquals(0,c.summary.steps)
    }
    @Test fun `minute boundary retains a real final event without making up additional steps`() {
        val c=core();event(c,RunSensorKind.STEP_DETECTOR,start+60_000_000_000,floatArrayOf(1f))
        assertEquals(listOf(0,1),c.measurements(60).perMinuteSteps)
        assertEquals(1,c.measurements(60).stepCount)
        assertTrue(runCatching { c.measurements(30) }.isFailure)
    }
    @Test fun `stopped sessions cannot be resumed and discarded raw data never reaches summary`() {
        val c=core();event(c,RunSensorKind.ACCELEROMETER,start+100_000_000)
        event(c,RunSensorKind.GYROSCOPE,start+110_000_000)
        assertEquals(1,c.summary.pairedSamples);c.stop();c.discardSamples();c.resume()
        event(c,RunSensorKind.STEP_DETECTOR,start+1_000_000_000,floatArrayOf(1f))
        assertEquals(RunSensorPhase.STOPPED,c.summary.phase);assertEquals(0,c.summary.steps)
        assertTrue(c.snapshot().isEmpty());assertEquals(1,c.summary.pairedSamples)
    }
    @Test fun `readiness summary distinguishes registered hardware from actual callbacks`() {
        val c=core();assertFalse(c.summary.sampled)
        event(c,RunSensorKind.STEP_DETECTOR,start+50_000_000,floatArrayOf(1f))
        event(c,RunSensorKind.ACCELEROMETER,start+100_000_000)
        event(c,RunSensorKind.GYROSCOPE,start+110_000_000)
        assertTrue(c.summary.sampled)
    }
    @Test fun `export includes only bounded sensor counters and policy shapes without sensor vectors or positions`() {
        val sensors=RunSensorSummary(RunSensorPhase.STOPPED,true,false,true,true,3,3,10,10,2,0,true)
        val requirements=RunRuntimeRequirements(RuntimeCheckShape.EMPTY_ARRAY,RuntimeCheckShape.EMPTY_ARRAY,true,true,RuntimeSwitch.DISABLED,RuntimeSwitch.DISABLED,RuntimeSwitch.ENABLED)
        val text=PreparationExport.encode(UploadFixture.config,1,1,null,sensors=sensors,requirements=requirements)
        val root=Json.parseToJsonElement(text).jsonObject
        val report=root.getValue("run_sensor_check").jsonObject
        assertEquals(true,report.getValue("completed").jsonPrimitive.boolean)
        assertEquals(3,report.getValue("observed_steps").jsonPrimitive.int)
        assertEquals(setOf("phase","completed","sampled","step_detector","step_counter","accelerometer","gyroscope","step_callbacks","observed_steps",
            "acceleration_callbacks","gyroscope_callbacks","paired_samples","rejected"),report.keys)
        assertFalse(report.toString().contains("time"));assertFalse(report.toString().contains("latitude"))
        assertEquals("EMPTY_ARRAY",root.getValue("execution_requirements").jsonObject.getValue("run_checks_shape").jsonPrimitive.content)
    }
    @Test fun `diagnostic runtime support matches automatic execution for explicit empty marker`() {
        for(shape in listOf(RuntimeCheckShape.EMPTY_STRING,RuntimeCheckShape.NULL,RuntimeCheckShape.MISSING,RuntimeCheckShape.NONEMPTY_ARRAY)) {
            val requirements=RunRuntimeRequirements(shape,RuntimeCheckShape.EMPTY_ARRAY,true,true,RuntimeSwitch.DISABLED,RuntimeSwitch.DISABLED,RuntimeSwitch.ENABLED)
            val text=PreparationExport.encode(UploadFixture.config,1,1,null,requirements=requirements)
            val report=Json.parseToJsonElement(text).jsonObject.getValue("execution_requirements").jsonObject
            assertEquals(shape==RuntimeCheckShape.EMPTY_STRING,report.getValue("supported").jsonPrimitive.boolean)
            assertEquals(requirements.automaticSupported,report.getValue("supported").jsonPrimitive.boolean)
        }
    }
    @Test fun `unknown null nonempty and opaque runtime check configuration is not silently skipped`() {
        val base=Json.parseToJsonElement("""{"run_check":[],"android_cheats":[],"gyroscope_type":1,"is_check_geetest":0,"is_check_shumeng":0,"is_check_deepkonw":0,"robot_module":false,"is_check_device":0,"is_check_geedevice":0,"is_check_time":1}""").jsonObject
        assertTrue(RunRuntimeRequirements.inspect(base).supported)
        assertFalse(RunRuntimeRequirements.inspect(JsonObject(base+mapOf("robot_module" to JsonPrimitive("false")))).supported)
        for(value in listOf(JsonNull,JsonPrimitive(""),JsonPrimitive("unrecognized"),JsonArray(listOf(JsonPrimitive(1))))) {
            val result=RunRuntimeRequirements.inspect(JsonObject(base+mapOf("run_check" to value)))
            assertFalse(result.supported)
        }
        assertTrue(RunRuntimeRequirements.inspect(JsonObject(base+mapOf("run_check" to JsonPrimitive("[]")))).supported)
        assertFalse(RunRuntimeRequirements.inspect(JsonObject(base-"run_check")).supported)
    }
}
