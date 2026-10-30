package io.github.ibudaoa.capture

import kotlinx.serialization.json.JsonPrimitive

/** Process-only display preview; does not contain or persist credentials, IDs, or raw responses. */
data class AccountProfilePreview(val name: String?, val schoolName: String?)

object AccountProfileDecoder {
    fun decode(bytes: ByteArray): AccountProfilePreview? {
        val data = BeforeRunDecoder.decodeSuccessfulData(bytes)
        fun text(key: String): String? {
            val value = (data[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim() ?: return null
            if (value.isEmpty() || value.length > 64 || value.any { it.isISOControl() } ||
                Regex("\\p{Nd}{6,}").containsMatchIn(value) || value.contains("://") || value.contains('@')) return null
            return value
        }
        val name = text("name")
        val school = text("school_name")
        return if (name == null && school == null) null else AccountProfilePreview(name, school)
    }
}
