package io.github.ibudaoa.capture

import kotlinx.serialization.json.JsonPrimitive

/** Sanitized display fields only; never credentials, IDs, or raw responses. */
data class AccountProfilePreview(val name: String?, val schoolName: String?)

object AccountProfileDecoder {
    internal fun validName(value: String): Boolean = value.isNotBlank() && value == value.trim() &&
        value.length <= 64 && value.none(Char::isISOControl) &&
        !Regex("\\p{Nd}{6,}").containsMatchIn(value) && !value.contains("://") && !value.contains('@')

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
