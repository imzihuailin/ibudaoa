package io.github.ibudaoa.capture

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class AccountProfileDecoderTest {
    private val data = """{"name":"测试用户","school_name":"测试大学","student_num":"123456789","token":"PRIVATE_TOKEN_SENTINEL","uid":"PRIVATE_ID_SENTINEL"}"""

    @Test fun `only profile name and school are projected from plain response`() {
        val profile = AccountProfileDecoder.decode("""{"status":1,"data":$data}""".toByteArray())!!
        assertEquals(AccountProfilePreview("测试用户", "测试大学"), profile)
        assertFalse(profile.toString().contains("PRIVATE"))
        assertFalse(profile.toString().contains("123456789"))
        val report = ConnectionReport.encode(CaptureState(profile = profile), false, false)
        assertFalse(report.contains("测试用户")); assertFalse(report.contains("PRIVATE"))
    }

    @Test fun `encrypted official envelope is decoded but never copied into the result`() {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(BeforeRunDecoder.RESPONSE_KEY.toByteArray(), "AES"),
            IvParameterSpec(BeforeRunDecoder.RESPONSE_IV.toByteArray()))
        val encoded = Base64.getEncoder().encodeToString(cipher.doFinal(data.toByteArray()))
        val profile = AccountProfileDecoder.decode("""{"status":1,"is_encrypt":1,"data":"$encoded"}""".toByteArray())!!
        assertEquals("测试用户", profile.name); assertFalse(profile.toString().contains("PRIVATE"))
    }

    @Test fun `untrusted values and failed account state do not appear in profile`() {
        val unsafe = """{"status":1,"data":{"name":"123456789","school_name":"https://invalid.example"}}"""
        assertNull(AccountProfileDecoder.decode(unsafe.toByteArray()))
        assertTrue(runCatching { AccountProfileDecoder.decode("""{"status":101,"data":$data}""".toByteArray()) }.isFailure)
        assertNull(AccountProfileDecoder.decode("""{"status":1,"data":{"uid":"PRIVATE"}}""".toByteArray()))
    }
}
