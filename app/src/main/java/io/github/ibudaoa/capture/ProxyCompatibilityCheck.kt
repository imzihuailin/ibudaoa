package io.github.ibudaoa.capture

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.URL
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.*

/** Runs only through explicit ADB instrumentation. Uses ephemeral test keys and fake upstream IO.
 * Never starts VpnService, opens official apps, touches account storage, or contacts a server. */
class ProxyCompatibilityCheck:Instrumentation() {
    private var stage="initializing"
    private var threads=""
    private val trace=java.util.concurrent.ConcurrentLinkedQueue<String>()
    override fun onCreate(arguments:Bundle?) {super.onCreate(arguments);start()}
    override fun onStart() {
        val result=Bundle()
        try {
            verify()
            result.putBoolean("passed",true)
            result.putString("scope","release-local-tls-http-no-vpn-no-account")
            finish(Activity.RESULT_OK,result)
        } catch(error:Throwable) {
            result.putBoolean("passed",false)
            result.putString("stage",stage)
            result.putString("threads",threads)
            result.putString("trace",trace.take(24).joinToString(";"))
            result.putString("type",error.javaClass.name)
            result.putString("cause_type",error.cause?.javaClass?.name ?: "none")
            // Fixed method/class positions only. Exception messages may contain protocol material.
            result.putString("frames",error.stackTrace.take(8).joinToString(";"){it.className+"."+it.methodName})
            finish(Activity.RESULT_CANCELED,result)
        }
    }
    private fun verify() {
        val keys=KeyPairGenerator.getInstance("RSA").apply {initialize(2048)}
        val ca=keys.generateKeyPair();val leaf=keys.generateKeyPair();val now=System.currentTimeMillis()
        val root=CertificateIssuer.root(ca.public,ca.private,now)
        val cert=CertificateIssuer.leaf(root,ca.private,leaf.public,now)
        val identity=KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null);setKeyEntry("local-test",leaf.private,"local-test".toCharArray(),arrayOf(cert,root))
        }
        val km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {init(identity,"local-test".toCharArray())}
        val serverTls=SSLContext.getInstance("TLS").apply {init(km.keyManagers,null,null)}
        val trust=KeyStore.getInstance(KeyStore.getDefaultType()).apply {load(null);setCertificateEntry("local-test",root)}
        val tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {init(trust)}
        val clientTls=SSLContext.getInstance("TLS").apply {init(null,tm.trustManagers,null)}
        val received=CountDownLatch(5)
        val upstream=CaptureHttpTransport(open={FakeConnection()})
        ReadOnlyProxy(serverTls,{received.countDown()},{},{},accountCheckOnly=true,accountMaintenance=true,onTrace={trace.add(it.toString())},upstream=upstream).use {proxy->
            Socket("127.0.0.1",proxy.port).use {plain->
                plain.soTimeout=5000
                val path=ReadOnlyPolicy.BEFORE_RUN
                stage="plain-absolute"
                send(plain,"POST http://${ReadOnlyPolicy.HOST}$path HTTP/1.1\r\nHost: ${ReadOnlyPolicy.HOST}\r\nContent-Length: 7\r\n\r\nkey=a+b")
                check(read(plain.getInputStream())==200)
                stage="plain-origin"
                send(plain,"POST $path HTTP/1.1\r\nHost: ${ReadOnlyPolicy.HOST}\r\nContent-Length: 7\r\n\r\nkey=a+b")
                check(read(plain.getInputStream())==200)
            }
            Socket("127.0.0.1",proxy.port).use {raw->
                raw.soTimeout=5000
                stage="connect"
                send(raw,"CONNECT api2.lptiyu.com:443 HTTP/1.1\r\nHost: api2.lptiyu.com:443\r\n\r\n")
                check(read(raw.getInputStream())==200)
                (clientTls.socketFactory.createSocket(raw,ReadOnlyPolicy.HOST,443,false) as SSLSocket).use {socket->
                    stage="tls"
                    socket.soTimeout=5000;socket.startHandshake()
                    val path=ReadOnlyPolicy.BEFORE_RUN
                    stage="startup-lookup"
                    send(socket,"POST ${ReadOnlyPolicy.BOOTSTRAP_IP} HTTP/1.1\r\nHost: api2.lptiyu.com\r\nContent-Length: 7\r\n\r\nkey=a+b")
                    check(read(socket.inputStream)==200)
                    stage="tls-body"
                    send(socket,"POST $path HTTP/1.1\r\nHost: api2.lptiyu.com\r\nContent-Length: 7\r\n\r\nkey=a+b")
                    check(read(socket.inputStream)==200)
                    send(socket,"POST $path HTTP/1.1\r\nHost: api2.lptiyu.com\r\nTransfer-Encoding: chunked\r\n\r\n4\r\nkey=\r\n3\r\na+b\r\n0\r\n\r\n")
                    check(read(socket.inputStream)==200)
                    send(socket,"POST $path HTTP/1.1\r\nHost: api2.lptiyu.com\r\nContent-Length: 7\r\nExpect: 100-continue\r\n\r\n")
                    check(read(socket.inputStream)==100);send(socket,"key=a+b");check(read(socket.inputStream)==200)
                }
            }
            check(received.await(3,TimeUnit.SECONDS))
        }
    }
    private fun send(socket:Socket,text:String){socket.getOutputStream().write(text.toByteArray());socket.getOutputStream().flush()}
    private fun line(input:InputStream):String {
        val out=ByteArrayOutputStream()
        while(true){val n=try {input.read()} catch(e:java.net.SocketTimeoutException){threads=Thread.getAllStackTraces().entries.filter {it.key.name.startsWith("capture-")}.joinToString(";"){it.key.name+":"+it.value.take(8).joinToString(","){f->f.className+"."+f.methodName}};throw e};check(n>=0);if(n==13){check(input.read()==10);return out.toString("ISO-8859-1")};out.write(n);check(out.size()<32768)}
    }
    private fun read(input:InputStream):Int {
        val status=line(input).split(' ')[1].toInt();var length=0
        while(true){val header=line(input);if(header.isEmpty())break
            if(header.substringBefore(':').equals("Content-Length",true))length=header.substringAfter(':').trim().toInt()}
        repeat(length){check(input.read()>=0)}
        return status
    }
    private class FakeConnection:HttpsURLConnection(URL("https://example.invalid")) {
        override fun connect(){}
        override fun disconnect(){}
        override fun usingProxy()=false
        override fun getCipherSuite()="local-test"
        override fun getLocalCertificates():Array<Certificate>?=null
        override fun getServerCertificates():Array<Certificate> = emptyArray()
        override fun getOutputStream():OutputStream=ByteArrayOutputStream()
        override fun getResponseCode()=200
        override fun getInputStream()="{\"status\":1,\"data\":{}}".byteInputStream()
        override fun getHeaderFields():Map<String,List<String>> = mapOf("Content-Type" to listOf("application/json"))
    }
}
