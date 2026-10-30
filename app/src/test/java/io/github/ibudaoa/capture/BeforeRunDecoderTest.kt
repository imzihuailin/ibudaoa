package io.github.ibudaoa.capture

import io.github.ibudaoa.diagnostics.DiagnosticCodec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BeforeRunDecoderTest {
    private val data = """{"school_id":11,"run_zone_id":433,"token":"SYNTHETIC_PRIVATE_VALUE","future_rule":{"mode":7}}"""

    @Test fun `plain successful response is captured without private values`() {
        val config = BeforeRunDecoder.decode("""{"status":1,"data":$data}""".toByteArray())
        assertEquals(433L, config.zoneId)
        assertFalse(DiagnosticCodec.encode(config).contains("SYNTHETIC_PRIVATE_VALUE"))
        assertEquals("number", config.structure!!.fieldTypes["future_rule.mode"])
        assertFalse(config.structure!!.fieldTypes.containsKey("token"))
    }

    @Test fun `encrypted response uses reference protocol without altering original envelope`() {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(BeforeRunDecoder.RESPONSE_KEY.toByteArray(), "AES"),
            IvParameterSpec(BeforeRunDecoder.RESPONSE_IV.toByteArray()))
        val encrypted = Base64.getEncoder().encodeToString(cipher.doFinal(data.toByteArray()))
        val original = """{"status":1,"is_encrypt":1,"data":"$encrypted"}""".toByteArray()
        val copy = original.clone()
        val config = BeforeRunDecoder.decode(original)
        assertEquals(433L, config.zoneId)
        assertTrue(copy.contentEquals(original))
    }

    @Test fun `unsuccessful response cannot be presented as connected configuration`() {
        listOf("""{"status":101,"data":$data}""", """{"status":0,"data":$data}""",
            """{"data":$data}""", """{"status":1,"is_encrypt":1,"data":"bad-cipher"}""").forEach {
            assertTrue(runCatching { BeforeRunDecoder.decode(it.toByteArray()) }.isFailure)
        }
    }

    @Test fun `known account failure statuses are distinct and never expose response data`() {
        for ((status, expected) in listOf(101 to CaptureProblem.ACCOUNT_EXPIRED, 102 to CaptureProblem.ACCOUNT_CHANGED)) {
            val error = runCatching { BeforeRunDecoder.decode("""{"status":$status,"data":$data}""".toByteArray()) }.exceptionOrNull()
            assertTrue(error is BeforeRunAccountException)
            assertEquals(expected, (error as BeforeRunAccountException).problem)
            assertFalse(error.toString().contains("SYNTHETIC_PRIVATE_VALUE"))
        }
    }

    @Test fun `partial captures carry missing-section evidence and schema survives export`() {
        val config = BeforeRunDecoder.decode("""{"status":1,"data":$data}""".toByteArray())
        assertTrue("time_rule_arr" in config.structure!!.missingSections)
        assertTrue("run_face_verify_config" in config.structure!!.missingSections)
        assertEquals(config, DiagnosticCodec.decode(DiagnosticCodec.encode(config)))
    }

    @Test fun `oversized and deeply nested responses cannot exhaust parsing`() {
        assertTrue(runCatching { BeforeRunDecoder.decode(ByteArray(DiagnosticCodec.MAX_BYTES + 1)) }.isFailure)
        assertTrue(runCatching { BeforeRunDecoder.decode(("[".repeat(40) + "]".repeat(40)).toByteArray()) }.isFailure)
    }
}
