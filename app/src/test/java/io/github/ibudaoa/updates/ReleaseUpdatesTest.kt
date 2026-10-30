package io.github.ibudaoa.updates

import java.io.ByteArrayInputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import org.junit.Assert.*
import org.junit.Test

class ReleaseUpdatesTest {
    private val repository=ReleaseRepository.parse("https://github.com/example/ibudaoa")!!
    private fun release(tag:String="v0.16.0",extra:String="")="""{"tag_name":"$tag","draft":false,"prerelease":false$extra}""".toByteArray()
    @Test fun `repository only accepts a plain public GitHub project URL`() {
        assertEquals(repository,ReleaseRepository.parse("https://github.com/example/ibudaoa.git/"))
        for(url in listOf("","http://github.com/example/ibudaoa","https://github.com.evil.test/example/ibudaoa",
            "https://user:secret@github.com/example/ibudaoa","https://github.com:443/example/ibudaoa",
            "https://github.com/example/ibudaoa?token=private","https://github.com/example/ibudaoa#fragment",
            "https://github.com/example/ibudaoa/releases","https://github.com/example/%2e%2e"))assertNull(url,ReleaseRepository.parse(url))
    }
    @Test fun `semantic version ordering handles numeric core and prerelease without recommending downgrade`() {
        assertTrue(ReleaseVersion.parse("0.16.10")!!>ReleaseVersion.parse("0.16.9")!!)
        assertTrue(ReleaseVersion.parse("v0.16.0")!!>ReleaseVersion.parse("0.16.0-rc1")!!)
        assertTrue(ReleaseVersion.parse("0.16.0-rc.10")!!>ReleaseVersion.parse("0.16.0-rc.2")!!)
        assertEquals(ReleaseVersion.parse("0.16.0+one"),ReleaseVersion.parse("0.16.0+two"))
        for(tag in listOf("latest","1.02.3","1.2","1.2.3-01","1.2.3-rc..1","1.2.3+..","9".repeat(150)))assertNull(tag,ReleaseVersion.parse(tag))
    }
    @Test fun `stable release becomes available over candidate but old remote versions remain current`() {
        val result=ReleaseUpdateDecoder.decode(release(),repository,"0.16.0-rc1")
        assertEquals(UpdatePhase.AVAILABLE,result.phase)
        assertEquals("https://github.com/example/ibudaoa/releases/tag/v0.16.0",result.releaseUrl)
        assertEquals(UpdatePhase.CURRENT,ReleaseUpdateDecoder.decode(release(),repository,"0.16.1").phase)
        assertEquals(UpdatePhase.UNSUPPORTED,ReleaseUpdateDecoder.decode(release(),repository,"unknown").phase)
    }
    @Test fun `draft prerelease malformed or foreign redirect cannot be offered as an update`() {
        for(bytes in listOf(release().toString(Charsets.UTF_8).replace("\"draft\":false","\"draft\":true").toByteArray(),
            release().toString(Charsets.UTF_8).replace("\"prerelease\":false","\"prerelease\":true").toByteArray(),
            release("v0.17.0-beta"),release(extra=",\"html_url\":\"https://evil.test/download.apk\""),"{}".toByteArray()))
            assertTrue(runCatching { ReleaseUpdateDecoder.decode(bytes,repository,"0.16.0") }.isFailure)
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
    @Test fun `transport reads only public latest release with GET no credentials and releases connection`() {
        lateinit var fake:Fake
        val client=ReleaseUpdateClient { url ->
            assertEquals(repository.apiUrl,url.toString());Fake(url,200,release()).also { fake=it }
        }
        assertEquals(UpdatePhase.AVAILABLE,client.check(repository,"0.15.4").phase)
        assertEquals("GET",fake.requestMethod);assertFalse(fake.instanceFollowRedirects);assertFalse(fake.useCaches)
        assertNull(fake.getRequestProperty("Authorization"));assertNull(fake.getRequestProperty("Cookie"))
        assertTrue(fake.disconnected);client.close()
    }
    @Test fun `not found rate limits redirects server failures and oversized payload are distinct from current`() {
        for((code,phase) in listOf(404 to UpdatePhase.NO_RELEASE,403 to UpdatePhase.RATE_LIMITED,429 to UpdatePhase.RATE_LIMITED,
            302 to UpdatePhase.FAILED,500 to UpdatePhase.FAILED)) {
            val client=ReleaseUpdateClient { Fake(it,code,release()) }
            assertEquals(phase,client.check(repository,"0.16.0").phase);client.close()
        }
        val large=ReleaseUpdateClient { Fake(it,200,ByteArray(524_289)) }
        assertEquals(UpdatePhase.FAILED,large.check(repository,"0.16.0").phase);large.close()
    }
    @Test fun `cancelled client cannot start another request and connection creation failure stays recoverable`() {
        var calls=0
        val client=ReleaseUpdateClient { calls++;Fake(it,200,release()) }
        client.close();assertEquals(UpdatePhase.FAILED,client.check(repository,"0.16.0").phase);assertEquals(0,calls)
        val failure=ReleaseUpdateClient { throw java.io.IOException("synthetic") }
        assertEquals(UpdatePhase.FAILED,failure.check(repository,"0.16.0").phase)
    }
}
