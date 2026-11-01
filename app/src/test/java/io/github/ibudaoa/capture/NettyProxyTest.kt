package io.github.ibudaoa.capture

import java.io.*
import java.net.Socket
import java.net.URL
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.*
import org.junit.Assert.*
import org.junit.Test

class NettyProxyTest {
    private class FakeConnection(private val reply:ByteArray, private val onSent:(ByteArray)->Unit, private val disconnects:AtomicInteger,private val encoding:String?=null):HttpsURLConnection(URL("https://example.invalid")) {
        val sent=ByteArrayOutputStream()
        override fun connect(){}
        override fun disconnect(){disconnects.incrementAndGet()}
        override fun usingProxy()=false
        override fun getOutputStream():OutputStream=object:FilterOutputStream(sent) {override fun close(){onSent(sent.toByteArray())}}
        override fun getResponseCode()=200
        override fun getInputStream()=reply.inputStream()
        override fun getHeaderFields():Map<String,List<String>> = mapOf("Content-Type" to listOf("application/json")) + (encoding?.let {mapOf("Content-Encoding" to listOf(it))} ?: emptyMap())
        override fun getCipherSuite()="test-only"
        override fun getLocalCertificates():Array<Certificate>?=null
        override fun getServerCertificates():Array<Certificate> = emptyArray()
    }
    private class Harness(onResponse:(ByteArray)->Unit={}, accountOnly:Boolean=false, failNetwork:Boolean=false, encoding:String?=null, private val reply:ByteArray="{\"status\":1,\"data\":{}}".toByteArray()):Closeable {
        val opened=AtomicInteger();val disconnects=AtomicInteger()
        val bodies=CopyOnWriteArrayList<ByteArray>();val traces=CopyOnWriteArrayList<CaptureTrace>()
        val problems=CopyOnWriteArrayList<CaptureProblem>()
        val client:SSLContext
        val proxy:ReadOnlyProxy
        init {
            val generator=KeyPairGenerator.getInstance("RSA").apply {initialize(2048)}
            val ca=generator.generateKeyPair();val leaf=generator.generateKeyPair();val now=System.currentTimeMillis()
            val root=CertificateIssuer.root(ca.public,ca.private,now)
            val cert=CertificateIssuer.leaf(root,ca.private,leaf.public,now)
            val identity=KeyStore.getInstance(KeyStore.getDefaultType()).apply {load(null);setKeyEntry("server",leaf.private,"test".toCharArray(),arrayOf(cert,root))}
            val km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {init(identity,"test".toCharArray())}
            val tls=SSLContext.getInstance("TLS").apply {init(km.keyManagers,null,null)}
            val trust=KeyStore.getInstance(KeyStore.getDefaultType()).apply {load(null);setCertificateEntry("root",root)}
            val tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {init(trust)}
            client=SSLContext.getInstance("TLS").apply {init(null,tm.trustManagers,null)}
            val transport=CaptureHttpTransport(trace={traces.add(it)},open={opened.incrementAndGet();FakeConnection(reply,{body->if(failNetwork)throw IOException("fake network failure");bodies.add(body)},disconnects,encoding)})
            proxy=ReadOnlyProxy(tls,onResponse,{},{problems.add(it)},accountCheckOnly=accountOnly,onTrace={traces.add(it)},upstream=transport)
        }
        fun tls():SSLSocket {
            val socket=Socket("127.0.0.1",proxy.port).apply {soTimeout=5000}
            socket.getOutputStream().write("CONNECT api2.lptiyu.com:443 HTTP/1.1\r\nHost: api2.lptiyu.com:443\r\n\r\n".toByteArray())
            socket.getOutputStream().flush()
            assertEquals(200,read(socket.getInputStream()).first)
            return (client.socketFactory.createSocket(socket,ReadOnlyPolicy.HOST,443,true) as SSLSocket).apply {soTimeout=5000;startHandshake()}
        }
        override fun close(){proxy.close()}
    }
    companion object {
        private fun line(input:InputStream):String {
            val out=ByteArrayOutputStream()
            while(true){val n=input.read();check(n>=0);if(n==13){check(input.read()==10);return out.toString("ISO-8859-1")};out.write(n);check(out.size()<65536)}
        }
        private fun read(input:InputStream):Pair<Int,ByteArray> {
            val first=line(input);val code=first.split(' ')[1].toInt();var length=0
            while(true){val header=line(input);if(header.isEmpty())break
                if(header.substringBefore(':').equals("Content-Length",true))length=header.substringAfter(':').trim().toInt()}
            return code to ByteArray(length).also {bytes->var n=0;while(n<length){val count=input.read(bytes,n,length-n);check(count>0);n+=count}}
        }
        private fun request(body:String="key=a+b",extra:String="",target:String=ReadOnlyPolicy.BEFORE_RUN)=
            "POST $target HTTP/1.1\r\nHost: api2.lptiyu.com\r\nContent-Length: ${body.toByteArray().size}\r\n$extra\r\n$body"
        private fun send(socket:Socket,text:String){socket.getOutputStream().write(text.toByteArray());socket.getOutputStream().flush()}
    }
    @Test fun `chunked request decoded without changing signed bytes and connection remains reusable`() {
        Harness().use {h->h.tls().use {socket->
            send(socket,"POST ${ReadOnlyPolicy.BEFORE_RUN} HTTP/1.1\r\nHost: api2.lptiyu.com\r\nTransfer-Encoding: chunked\r\n\r\n4\r\nkey=\r\n3\r\na+b\r\n0\r\n\r\n")
            assertEquals(200,read(socket.inputStream).first)
            send(socket,request("key=c+d"));assertEquals(200,read(socket.inputStream).first)
            assertEquals(listOf("key=a+b","key=c+d"),h.bodies.map {it.toString(Charsets.UTF_8)})
            assertEquals(0,h.disconnects.get())
        }}
    }
    @Test fun `continue is emitted before body and only after allowlist checks`() {
        Harness().use {h->h.tls().use {socket->
            val text=request(extra="Expect: 100-continue\r\n")
            send(socket,text.substringBefore("\r\n\r\n")+"\r\n\r\n")
            assertEquals(100,read(socket.inputStream).first);assertEquals(0,h.opened.get())
            send(socket,"key=a+b");assertEquals(200,read(socket.inputStream).first)
        }
        h.tls().use {socket->send(socket,request(extra="Expect: 100-continue\r\n",target="/v3/api.php/Run/stopRunV278"))
            assertEquals(403,read(socket.inputStream).first);assertEquals(1,h.opened.get())}
        }
    }
    @Test fun `ambiguous framing oversize headers and movement never reach upstream`() {
        Harness().use {h->
            val cases=listOf(
                "Content-Length: 0\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n",
                "Content-Length: 0\r\nContent-Length: 0\r\n\r\n",
                "Content-Length: 99999999\r\n\r\n",
                "X: ${"x".repeat(40000)}\r\n\r\n")
            for(headers in cases)h.tls().use {socket->send(socket,"POST ${ReadOnlyPolicy.BEFORE_RUN} HTTP/1.1\r\nHost: api2.lptiyu.com\r\n$headers");assertTrue(read(socket.inputStream).first>=400)}
            assertEquals(0,h.opened.get());assertTrue(h.traces.any {it.failure!=CaptureFailure.NONE})
        }
    }
    @Test fun `pipelined requests keep response order while idle clients do not exhaust workers`() {
        Harness().use {h->
            val idle=List(12){Socket("127.0.0.1",h.proxy.port)}
            try {h.tls().use {socket->send(socket,request("one=1")+request("two=2"));assertEquals(200,read(socket.inputStream).first);assertEquals(200,read(socket.inputStream).first)}
                assertEquals(listOf("one=1","two=2"),h.bodies.map {it.toString(Charsets.UTF_8)})
            }finally{idle.forEach {it.close()}}
        }
    }
    @Test fun `complete received response survives disconnect during local processing`() {
        val entered=CountDownLatch(1);val finish=CountDownLatch(1);val saved=CountDownLatch(1)
        Harness(onResponse={entered.countDown();finish.await(3,TimeUnit.SECONDS);saved.countDown()}).use {h->
            h.tls().use {socket->send(socket,request());assertTrue(entered.await(3,TimeUnit.SECONDS));read(socket.inputStream)}
            finish.countDown();assertTrue(h.proxy.finishReceived());assertTrue(saved.await(2,TimeUnit.SECONDS))
        }
    }
    @Test fun `network failures are not misreported as request format errors`() {
        Harness(failNetwork=true).use {h->h.tls().use {socket->
            send(socket,request());assertEquals(502,read(socket.inputStream).first)
            assertTrue(h.problems.contains(CaptureProblem.NETWORK));assertFalse(h.problems.contains(CaptureProblem.REQUEST_FORMAT))
        }}
    }
    @Test fun `valid token header names and tabs do not corrupt signed body bytes`() {
        Harness().use {h->h.tls().use {socket->
            send(socket,request(extra="X_Test: \tvalue\r\n"));assertEquals(200,read(socket.inputStream).first)
            assertEquals("key=a+b",h.bodies.single().toString(Charsets.UTF_8))
        }}
    }
    @Test fun `gzip stays intact for official client and is decoded only for local inspection`() {
        val plain="{\"status\":1,\"data\":{}}".toByteArray()
        val out=ByteArrayOutputStream();java.util.zip.GZIPOutputStream(out).use {it.write(plain)}
        val received=CountDownLatch(1)
        Harness(onResponse={assertArrayEquals(plain,it);received.countDown()},encoding="gzip",reply=out.toByteArray()).use {h->h.tls().use {socket->
            send(socket,request());assertArrayEquals(out.toByteArray(),read(socket.inputStream).second)
            assertTrue(received.await(2,TimeUnit.SECONDS))
        }}
    }
    @Test fun `truncated body is classified and cannot initiate an upstream request`() {
        Harness().use {h->h.tls().use {socket->
            send(socket,request().substringBefore("\r\n\r\n")+"\r\n\r\nabc")
        }
        h.proxy.finishReceived()
        assertEquals(0,h.opened.get())
        }
    }
    @Test fun `plain explicit HTTP absolute and origin forms preserve bodies and reuse connection`() {
        Harness().use {h->Socket("127.0.0.1",h.proxy.port).use {socket->
            socket.soTimeout=5000
            send(socket,request(target="http://${ReadOnlyPolicy.HOST}${ReadOnlyPolicy.BEFORE_RUN}"))
            assertEquals(200,read(socket.inputStream).first)
            send(socket,request("key=c+d"));assertEquals(200,read(socket.inputStream).first)
            assertEquals(listOf("key=a+b","key=c+d"),h.bodies.map {it.toString(Charsets.UTF_8)})
        }}
    }
    @Test fun `a new TLS tunnel works after a plain HTTP connection closes`() {
        Harness().use {h->
            Socket("127.0.0.1",h.proxy.port).use {socket->
                socket.soTimeout=5000
                send(socket,request(target="http://${ReadOnlyPolicy.HOST}${ReadOnlyPolicy.BEFORE_RUN}"))
                assertEquals(200,read(socket.inputStream).first)
                send(socket,request());assertEquals(200,read(socket.inputStream).first)
            }
            h.tls().use {socket->send(socket,request());assertEquals(200,read(socket.inputStream).first)}
            assertEquals(3,h.opened.get())
        }
    }
    @Test fun `account capture forwards only the audited startup lookup before profile requests`() {
        Harness(accountOnly=true).use {h->
            h.tls().use {socket->
                send(socket,request(target=ReadOnlyPolicy.BOOTSTRAP_IP));assertEquals(200,read(socket.inputStream).first)
                send(socket,request(target="/v3/api.php/System/notAudited"));assertEquals(403,read(socket.inputStream).first)
            }
            h.tls().use {socket->
                send(socket,request(target="/v3/api.php/Run/getTimestampV278"));assertEquals(403,read(socket.inputStream).first)
            }
            assertEquals(1,h.opened.get())
        }
    }
    @Test fun `plain explicit HTTP cannot bypass host or platform movement policy`() {
        Harness().use {h->
            for(target in listOf("http://evil.invalid${ReadOnlyPolicy.BEFORE_RUN}",
                "http://${ReadOnlyPolicy.HOST}/v3/api.php/Run/stopRunV278")) {
                Socket("127.0.0.1",h.proxy.port).use {socket->socket.soTimeout=5000;send(socket,request(target=target));assertEquals(403,read(socket.inputStream).first)}
            }
            assertEquals(0,h.opened.get())
        }
    }
    @Test fun `thirty complete local TLS acquisitions lose no responses`() {
        val received=CountDownLatch(30)
        Harness(onResponse={received.countDown()}).use {h->
            repeat(30){h.tls().use {socket->send(socket,request());assertEquals(200,read(socket.inputStream).first)}}
            assertTrue(received.await(3,TimeUnit.SECONDS));assertEquals(30,h.opened.get())
        }
    }
}
