package io.github.ibudaoa.capture

import java.io.ByteArrayOutputStream

/** One cancellation owner over system-verified HTTPS. Never enables platform movement paths. */
internal class AccountReadTransport(private val maintenance:Boolean=false, trace:(CaptureTrace)->Unit={}):AccountReadConnection {
    private val transport=CaptureHttpTransport(trace)
    override fun send(request:LocalRequest):ByteArray {
        require(request.target==ReadOnlyPolicy.USER_PROFILE || maintenance && request.target in setOf(ReadOnlyPolicy.BEFORE_RUN,AccountProtocol.REFRESH))
        val reply=transport.execute(request,maintenance)
        try {
            if(reply.status!=200)throw AccountReadProbe.HttpFailure()
            return CaptureHttpTransport.decoded(reply)
        } finally { reply.body.fill(0) }
    }
    override fun close()=transport.close()
}
internal class SecretByteOutput:ByteArrayOutputStream() { fun wipe(){buf.fill(0);reset()} }
