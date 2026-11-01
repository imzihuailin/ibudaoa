package io.github.ibudaoa.capture

import java.io.IOException

/** Errors carry a fixed category only. No original request material leaves the decoder. */
class CaptureProtocolException internal constructor(internal val reason:CaptureFailure=CaptureFailure.REQUEST_LINE):IOException("Local proxy protocol: ${reason.name}")
data class LocalRequest(val method:String,val target:String,val headers:List<Pair<String,String>>,val body:ByteArray)
/** Compatibility bound used by request projection and response readers; parsing is handled by Netty. */
object HttpRequestReader { const val MAX_BODY=2*1024*1024 }
