package io.github.ibudaoa.capture

import org.junit.Assert.*
import org.junit.Test

class ProxyRequestTargetTest {
    @Test fun `absolute origin and tunnel forms resolve only to pinned path`() {
        val path=ReadOnlyPolicy.USER_PROFILE
        assertEquals(path,ProxyRequestTarget.path("http://${ReadOnlyPolicy.HOST}$path",listOf(ReadOnlyPolicy.HOST),false))
        assertEquals(path,ProxyRequestTarget.path(path,listOf("API2.LPTIYU.COM:443"),true))
        assertTrue(ProxyRequestTarget.connect("API2.LPTIYU.COM.:443"))
        assertFalse(ProxyRequestTarget.connect("example.invalid:443"))
        assertFalse(ProxyRequestTarget.connect("api2.lptiyu.com:80"))
    }
    @Test fun `userinfo query fragments host spoofing and nonstandard ports remain blocked`() {
        val path=ReadOnlyPolicy.USER_PROFILE
        for(target in listOf("http://evil.invalid$path","http://api2.lptiyu.com.evil.invalid$path",
            "http://secret@api2.lptiyu.com$path","http://api2.lptiyu.com:8080$path",
            "http://api2.lptiyu.com$path?token=FAKE",path+"#fake","//evil.invalid$path"))
            assertNull(ProxyRequestTarget.path(target,listOf(ReadOnlyPolicy.HOST),false))
        assertNull(ProxyRequestTarget.path(path,listOf(ReadOnlyPolicy.HOST,ReadOnlyPolicy.HOST),false))
        assertNull(ProxyRequestTarget.path(path,listOf("evil.invalid"),false))
    }
}
