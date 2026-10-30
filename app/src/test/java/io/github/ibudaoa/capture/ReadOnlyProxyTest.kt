package io.github.ibudaoa.capture

import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadOnlyProxyTest {
    @Test fun `TLS protected movement request is blocked without any upstream connection`() =
        assertDenied("/v3/api.php/Run/stopRunV278")

    @Test fun `account only purpose cannot forward or replace the saved school configuration`() =
        assertDenied(ReadOnlyPolicy.BEFORE_RUN,true)
    @Test fun `ordinary collection cannot forward a token refresh`() = assertDenied(AccountProtocol.REFRESH)
    @Test fun `account maintenance still blocks platform movement and signup writes`() {
        assertDenied("/v3/api.php/Run/stopRunV278",true,true)
        assertDenied("/v3/api.php/My/getSignupPoints",true,true)
    }

    private fun assertDenied(target:String,accountOnly:Boolean=false,maintenance:Boolean=false) {
        val ca = key(); val leaf = key(); val now = System.currentTimeMillis()
        val root = CertificateIssuer.root(ca.public, ca.private, now)
        val certificate = CertificateIssuer.leaf(root, ca.private, leaf.public, now)
        val identity = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null); setKeyEntry("server", leaf.private, "test".toCharArray(), arrayOf(certificate, root))
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(identity, "test".toCharArray()) }
        val serverTls = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        val trust = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("test-ca", root) }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
        val clientTls = SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }
        val blocked = AtomicInteger()
        val captured = AtomicInteger()
        val proxy = ReadOnlyProxy(serverTls, { captured.incrementAndGet() }, { blocked.incrementAndGet() }, {},accountCheckOnly=accountOnly,accountMaintenance=maintenance)
        val worker = thread(isDaemon = true) { proxy.serve() }
        try {
            Socket("127.0.0.1", proxy.port).use { raw ->
                raw.soTimeout = 5000
                raw.getOutputStream().write("CONNECT api2.lptiyu.com:443 HTTP/1.1\r\nHost: api2.lptiyu.com:443\r\n\r\n".toByteArray())
                raw.getOutputStream().flush()
                val header = StringBuilder()
                while (!header.endsWith("\r\n\r\n")) {
                    val byte = raw.getInputStream().read()
                    check(byte >= 0 && header.length < 1024)
                    header.append(byte.toChar())
                }
                assertTrue(header.startsWith("HTTP/1.1 200"))
                (clientTls.socketFactory.createSocket(raw, ReadOnlyPolicy.HOST, 443, false) as SSLSocket).use { tls ->
                    tls.soTimeout = 5000; tls.startHandshake()
                    tls.outputStream.write("POST $target HTTP/1.1\r\nHost: api2.lptiyu.com\r\nContent-Length: 0\r\n\r\n".toByteArray())
                    tls.outputStream.flush()
                    val reply = tls.inputStream.readBytes().toString(Charsets.UTF_8)
                    assertTrue(reply.startsWith("HTTP/1.1 403"))
                    assertTrue(reply.contains("只读诊断已阻止"))
                }
            }
            assertEquals(1, blocked.get()); assertEquals(0, captured.get())
        } finally { proxy.close(); worker.join(2000) }
        assertFalse(worker.isAlive)
    }

    private fun key() = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
}
