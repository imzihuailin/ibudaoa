package io.github.ibudaoa.capture

import java.net.URI

/** Interpret explicit HTTP proxy forms without ever broadening the pinned upstream host. */
internal object ProxyRequestTarget {
    fun connect(authority:String):Boolean = runCatching {
        val uri=URI("https://$authority")
        pinned(uri) && (uri.port==-1 || uri.port==443) && uri.rawPath.isNullOrEmpty() &&
            uri.rawQuery==null && uri.rawFragment==null
    }.getOrDefault(false)

    fun path(target:String,hosts:List<String>,tunneled:Boolean):String? = runCatching {
        if(hosts.size!=1)return null
        val authority=URI("https://${hosts.single()}")
        if(!pinned(authority) || !authority.rawPath.isNullOrEmpty() || authority.rawQuery!=null || authority.rawFragment!=null ||
            authority.port !in if(tunneled)setOf(-1,443) else setOf(-1,80,443)) return null
        if(target.startsWith('/')) {
            val uri=URI(target)
            if(uri.isAbsolute || uri.rawAuthority!=null || uri.rawQuery!=null || uri.rawFragment!=null) return null
            return uri.rawPath
        }
        if(tunneled)return null
        val uri=URI(target)
        if(!pinned(uri) || uri.rawQuery!=null || uri.rawFragment!=null)return null
        val expected=when(uri.scheme?.lowercase()) {"http"->80;"https"->443;else->return null}
        if(uri.port!=-1 && uri.port!=expected)return null
        uri.rawPath.takeIf { !it.isNullOrEmpty() }
    }.getOrNull()

    private fun pinned(uri:URI):Boolean = uri.rawUserInfo==null &&
        uri.host?.removeSuffix(".")?.equals(ReadOnlyPolicy.HOST,true)==true
}
