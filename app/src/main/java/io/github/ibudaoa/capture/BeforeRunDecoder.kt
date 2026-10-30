package io.github.ibudaoa.capture

import io.github.ibudaoa.diagnostics.ConfigStructure
import io.github.ibudaoa.diagnostics.DiagnosticCodec
import io.github.ibudaoa.diagnostics.DiagnosticConfig
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Only a known status category is retained; response contents and credentials are never exposed. */
class BeforeRunAccountException(val problem: CaptureProblem) : Exception(problem.userMessage)

/** Passive response decoder only: never signs, creates or repeats a platform request. */
object BeforeRunDecoder {
    // Public protocol constants in the pinned Apache-2.0 reference; not account credentials.
    internal const val RESPONSE_KEY = "Wet2C8d34f62ndi3"
    internal const val RESPONSE_IV = "K6iv85jBD8jgf32D"

    fun decode(bytes: ByteArray): DiagnosticConfig {
        val data = decodeSuccessfulData(bytes)
        val safe = DiagnosticCodec.decode(data.toString())
        return safe.copy(structure = ConfigStructure.inspect(data))
    }

    internal fun decodeSuccessfulData(bytes: ByteArray): JsonObject {
        require(bytes.size <= DiagnosticCodec.MAX_BYTES) { "Response too large" }
        val text = bytes.toString(Charsets.UTF_8)
        DiagnosticCodec.validateInput(text)
        val envelope = Json.parseToJsonElement(text) as? JsonObject
            ?: error("Invalid response")
        val status = (envelope["status"] as? JsonPrimitive)?.intOrNull
        when (status) {
            101 -> throw BeforeRunAccountException(CaptureProblem.ACCOUNT_EXPIRED)
            102 -> throw BeforeRunAccountException(CaptureProblem.ACCOUNT_CHANGED)
        }
        require(status == 1) { "Account not confirmed" }
        val data = if ((envelope["is_encrypt"] as? JsonPrimitive)?.intOrNull == 1) {
            val encoded = envelope["data"] as? JsonPrimitive ?: error("Missing response")
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(RESPONSE_KEY.toByteArray(), "AES"),
                IvParameterSpec(RESPONSE_IV.toByteArray()))
            val clear = cipher.doFinal(Base64.getMimeDecoder().decode(encoded.content))
            try {
                require(clear.size <= DiagnosticCodec.MAX_BYTES) { "Response too large" }
                val plain = clear.toString(Charsets.UTF_8)
                DiagnosticCodec.validateInput(plain)
                Json.parseToJsonElement(plain) as? JsonObject
                    ?: error("Invalid response")
            } finally { clear.fill(0) }
        } else envelope["data"] as? JsonObject ?: error("Missing response")
        return data
    }
}
