package io.github.ibudaoa.capture

import java.io.InputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

class CaptureProtocolException : IOException("Unsupported local proxy request")

data class LocalRequest(val method: String, val target: String, val headers: List<Pair<String, String>>, val body: ByteArray)

/** Bounded HTTP/1 parser. Reject ambiguous framing instead of forwarding it. No input in errors. */
object HttpRequestReader {
    const val MAX_BODY = 2 * 1024 * 1024
    private const val MAX_HEADERS = 32 * 1024

    fun read(input: InputStream): LocalRequest? {
        val first = line(input, 8192) ?: return null
        val parts = first.split(' ')
        if (parts.size != 3 || parts[2] != "HTTP/1.1") throw CaptureProtocolException()
        if (parts.any { part -> part.isEmpty() || part.any { it.code < 33 || it.code > 126 } }) {
            throw CaptureProtocolException()
        }
        val headers = mutableListOf<Pair<String, String>>()
        var budget = first.length + 2
        while (true) {
            val value = line(input, 8192) ?: throw CaptureProtocolException()
            budget += value.length + 2
            if (budget > MAX_HEADERS || headers.size > 100) throw CaptureProtocolException()
            if (value.isEmpty()) break
            val separator = value.indexOf(':')
            if (separator < 1) throw CaptureProtocolException()
            val name = value.substring(0, separator)
            val content = value.substring(separator + 1).trim()
            if (!name.all { it.isLetterOrDigit() || it == '-' } ||
                content.any { it.code < 32 || it.code > 126 }) throw CaptureProtocolException()
            headers += name to content
        }
        val lengths = headers.filter { it.first.equals("Content-Length", true) }
        if (lengths.size > 1 || headers.any { it.first.equals("Transfer-Encoding", true) || it.first.equals("Expect", true) }) {
            throw CaptureProtocolException()
        }
        val length = if (lengths.isEmpty()) 0 else lengths.single().second.toIntOrNull()
            ?: throw CaptureProtocolException()
        if (length !in 0..MAX_BODY) throw CaptureProtocolException()
        if (parts[0] == "CONNECT" && length != 0) throw CaptureProtocolException()
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(body, offset, length - offset)
            if (count <= 0) throw CaptureProtocolException()
            offset += count
        }
        return LocalRequest(parts[0], parts[1], headers, body)
    }

    private fun line(input: InputStream, max: Int): String? {
        val bytes = ArrayList<Byte>()
        while (true) {
            val byte = input.read()
            if (byte < 0) {
                if (bytes.isEmpty()) return null
                throw CaptureProtocolException()
            }
            if (byte == 13) {
                if (input.read() != 10) throw CaptureProtocolException()
                return String(bytes.toByteArray(), StandardCharsets.US_ASCII)
            }
            if (byte == 10 || bytes.size >= max) throw CaptureProtocolException()
            bytes += byte.toByte()
        }
    }
}
