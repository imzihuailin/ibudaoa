package io.github.ibudaoa.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpRequestReaderTest {
    @Test fun `signed body bytes and header values are preserved`() {
        val request = read("POST /v3/api.php/Run2/beforeRunV260 HTTP/1.1\r\nHost: api2.lptiyu.com\r\nContent-Length: 7\r\n\r\nkey=a+b")!!
        assertEquals("key=a+b", request.body.toString(Charsets.UTF_8))
        assertEquals("api2.lptiyu.com", request.headers.first().second)
    }

    @Test fun `ambiguous body framing is never accepted`() {
        listOf("Content-Length: 0\r\nContent-Length: 0", "Transfer-Encoding: chunked", "Expect: 100-continue",
            "Content-Length: -1", "Content-Length: ${HttpRequestReader.MAX_BODY + 1}").forEach {
            rejects("POST / HTTP/1.1\r\n$it\r\n\r\n")
        }
    }

    @Test fun `truncation oversized headers and malformed lines fail predictably`() {
        rejects("POST / HTTP/1.1\r\nContent-Length: 7\r\n\r\nshort")
        rejects("POST / HTTP/1.1\n\n")
        rejects("POST / HTTP/1.1\r\nX: ${"a".repeat(8192)}\r\n\r\n")
        rejects("CONNECT api2.lptiyu.com:443 HTTP/1.1\r\nContent-Length: 1\r\n\r\nx")
        assertNull(read(""))
    }

    @Test fun `errors do not include original request or credentials`() {
        val error = runCatching { read("SECRET_TEST_INPUT\r\n") }.exceptionOrNull()!!
        assertTrue(error is CaptureProtocolException)
        assertTrue(!error.message.orEmpty().contains("SECRET_TEST_INPUT"))
    }

    private fun read(text: String) = HttpRequestReader.read(text.toByteArray().inputStream())
    private fun rejects(text: String) { assertTrue(runCatching { read(text) }.exceptionOrNull() is CaptureProtocolException) }
}
