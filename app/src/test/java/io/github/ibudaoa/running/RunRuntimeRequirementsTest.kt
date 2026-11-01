package io.github.ibudaoa.running

import io.github.ibudaoa.diagnostics.PreparationExport
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RunRuntimeRequirementsTest {
    private val base=Json.parseToJsonElement("""{"run_check":[],"android_cheats":[],"gyroscope_type":1,"is_check_device":0,"is_check_geedevice":0,"is_check_time":1,"is_check_geetest":0,"is_check_shumeng":0,"is_check_deepkonw":0,"robot_module":false}""").jsonObject
    @Test fun `device checks require explicit disabled flags and retain specific missing null and invalid states`() {
        for((key,problem) in listOf("is_check_device" to RunRequirementProblem.DEVICE,"is_check_geedevice" to RunRequirementProblem.GEE_DEVICE)) {
            for(value in listOf(JsonPrimitive(1),JsonNull,JsonPrimitive(true),JsonPrimitive(2),JsonArray(emptyList()))) {
                val result=RunRuntimeRequirements.inspect(JsonObject(base+(key to value)))
                assertFalse(result.automaticSupported);assertEquals(problem,result.blockingProblem)
            }
            val missing=RunRuntimeRequirements.inspect(JsonObject(base-key))
            assertEquals(problem,missing.blockingProblem)
            assertEquals(RuntimeSwitch.MISSING,if(key=="is_check_device")missing.deviceCheck else missing.geeDeviceCheck)
        }
        assertTrue(RunRuntimeRequirements.inspect(base).automaticSupported)
    }
    @Test fun `time checks permit both known modes but reject unknown configuration`() {
        for(n in 0..1)assertTrue(RunRuntimeRequirements.inspect(JsonObject(base+("is_check_time" to JsonPrimitive(n)))).automaticSupported)
        for(value in listOf(JsonNull,JsonPrimitive(true),JsonPrimitive(2)))
            assertEquals(RunRequirementProblem.TIME,RunRuntimeRequirements.inspect(JsonObject(base+("is_check_time" to value))).blockingProblem)
        assertEquals(RunRequirementProblem.TIME,RunRuntimeRequirements.inspect(JsonObject(base-"is_check_time")).blockingProblem)
    }
    @Test fun `each external verification requirement is independently fail closed`() {
        for(key in listOf("is_check_geetest","is_check_shumeng","is_check_deepkonw","robot_module")) {
            for(value in listOf(JsonNull,if(key=="robot_module")JsonPrimitive(true) else JsonPrimitive(1))) {
                val r=RunRuntimeRequirements.inspect(JsonObject(base+(key to value)))
                assertEquals(RunRequirementProblem.VERIFICATION,r.blockingProblem)
                assertNotEquals(RuntimeSwitch.DISABLED,r.verificationChecks[key])
            }
            assertFalse(RunRuntimeRequirements.inspect(JsonObject(base-key)).automaticSupported)
        }
    }
    @Test fun `known empty behavior forms remain compatible and nonempty checks remain unsupported`() {
        for(value in listOf(JsonArray(emptyList()),JsonPrimitive("[]"),JsonPrimitive("")))
            assertTrue(RunRuntimeRequirements.inspect(JsonObject(base+("run_check" to value))).automaticSupported)
        for(key in listOf("run_check","android_cheats")) {
            val r=RunRuntimeRequirements.inspect(JsonObject(base+(key to JsonArray(listOf(JsonPrimitive(1))))))
            assertFalse(r.automaticSupported)
        }
    }
    @Test fun `requirement summaries contain only known switch names and enums`() {
        val r=RunRuntimeRequirements.inspect(base).copy(verificationChecks=mapOf("private-identity" to RuntimeSwitch.ENABLED))
        val text=PreparationExport.encode(UploadFixture.config,1,1,null,requirements=r)
        val report=Json.parseToJsonElement(text).jsonObject.getValue("execution_requirements").jsonObject
        assertEquals("DISABLED",report.getValue("device_check").jsonPrimitive.content)
        assertEquals("ENABLED",report.getValue("time_check").jsonPrimitive.content)
        assertFalse(text.contains("private-identity"));assertFalse(report.toString().contains("latitude"))
    }
}
