package io.github.ibudaoa.updates
import java.io.ByteArrayInputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import org.junit.Assert.*
import org.junit.Test

class ReleaseUpdatesTest {
    private val repository=ReleaseRepository.parse("https://github.com/example/ibudaoa")!!
    private fun source(code:Int=101)= """
        versionCode = $code
        versionName = "1.0.3"
    """.trimIndent().toByteArray()
    @Test fun `repository only accepts a plain public GitHub project URL`() {
        assertEquals(repository,ReleaseRepository.parse("https://github.com/example/ibudaoa.git/"))
        for(url in listOf("","http://github.com/example/ibudaoa","https://github.com.evil.test/example/ibudaoa",
            "https://user:secret@github.com/example/ibudaoa","https://github.com:443/example/ibudaoa",
            "https://github.com/example/ibudaoa?token=private","https://github.com/example/ibudaoa#fragment",
            "https://github.com/example/ibudaoa/releases","https://github.com/example/%2e%2e"))assertNull(url,ReleaseRepository.parse(url))
    }
    private class Fake(url:URL,private val code:Int,private val bytes:ByteArray):HttpsURLConnection(url) {
        var disconnected=false
        override fun connect() {}
        override fun disconnect() { disconnected=true }
        override fun usingProxy()=false
        override fun getResponseCode()=code
        override fun getInputStream()=ByteArrayInputStream(bytes)
        override fun getCipherSuite()="FAKE"
        override fun getLocalCertificates():Array<java.security.cert.Certificate>?=null
        override fun getServerCertificates():Array<java.security.cert.Certificate> = emptyArray()
    }

    @Test fun `compares Android version codes instead of release names`() {
        assertEquals(UpdateSummary(UpdatePhase.AVAILABLE,"1.0.3"),SourceUpdateDecoder.decode(source(),100))
        assertEquals(UpdatePhase.CURRENT,SourceUpdateDecoder.decode(source(100),100).phase)
        assertEquals(UpdatePhase.CURRENT,SourceUpdateDecoder.decode(source(99),100).phase)
    }
    @Test fun `rejects missing duplicated executable malformed and oversized declarations`() {
        for(bytes in listOf("<html>error</html>".toByteArray(),source()+source(),
            "versionCode = getCode()\nversionName = \"1.0.3\"".toByteArray(),
            source().toString(Charsets.UTF_8).replace("101","999999999999999999").toByteArray(),
            ByteArray(SourceUpdateDecoder.MAX_BYTES+1))) {
            assertTrue(runCatching { SourceUpdateDecoder.decode(bytes,100) }.isFailure)
        }
    }
    @Test fun `mirrors fail over in order and valid response stops fallback`() {
        val urls=mutableListOf<String>();val connections=mutableListOf<Fake>()
        val client=ReleaseUpdateClient {
            urls+=it.toString()
            Fake(it,if(urls.size==1)503 else 200,source()).also { c -> connections+=c }
        }
        assertEquals(UpdatePhase.AVAILABLE,client.check(repository,100).phase)
        assertEquals(repository.updateUrls.take(2),urls)
        for(c in connections) {
            assertTrue(c.disconnected);assertEquals("GET",c.requestMethod)
            assertFalse(c.instanceFollowRedirects);assertFalse(c.useCaches)
            assertNull(c.getRequestProperty("Authorization"));assertNull(c.getRequestProperty("Cookie"))
        }
        client.close()
    }
    @Test fun `bad mirror payload falls back to official source`() {
        var calls=0
        val client=ReleaseUpdateClient { Fake(it,200,if(++calls<3)"garbage".toByteArray() else source()) }
        assertEquals(UpdatePhase.CURRENT,client.check(repository,101).phase);assertEquals(3,calls)
    }
    @Test fun `all errors remain failed instead of current`() {
        for(code in listOf(404,403,429,302,500)) {
            val client=ReleaseUpdateClient { Fake(it,code,source()) }
            assertEquals(UpdatePhase.FAILED,client.check(repository,100).phase);client.close()
        }
        val large=ReleaseUpdateClient { Fake(it,200,ByteArray(SourceUpdateDecoder.MAX_BYTES+1)) }
        assertEquals(UpdatePhase.FAILED,large.check(repository,100).phase)
        val failure=ReleaseUpdateClient { throw java.io.IOException("synthetic") }
        assertEquals(UpdatePhase.FAILED,failure.check(repository,100).phase)
    }
    @Test fun `cancellation prevents requests and fallback`() {
        var calls=0
        val closed=ReleaseUpdateClient { calls++;Fake(it,200,source()) }
        closed.close();assertEquals(UpdatePhase.FAILED,closed.check(repository,100).phase);assertEquals(0,calls)
        lateinit var client:ReleaseUpdateClient
        client=ReleaseUpdateClient { calls++;client.close();Fake(it,503,source()) }
        assertEquals(UpdatePhase.FAILED,client.check(repository,100).phase);assertEquals(1,calls)
    }
}
