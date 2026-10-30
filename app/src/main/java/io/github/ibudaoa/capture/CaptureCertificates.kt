package io.github.ibudaoa.capture

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager

/** Only the public CA is exportable. Its private signing key stays in Android Keystore. */
class CaptureCertificates(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "diagnostic-ca.cer"))

    fun root(): X509Certificate = synchronized(lock) {
        val store = keyStore()
        if (!store.containsAlias(ALIAS)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setKeySize(2048).setDigests(KeyProperties.DIGEST_SHA256)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1).build())
                generateKeyPair()
            }
        }
        val publicKey = store.getCertificate(ALIAS).publicKey
        val existing = runCatching {
            file.openRead().use { CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate }
        }.getOrNull()
        if (existing != null && existing.publicKey.encoded.contentEquals(publicKey.encoded) &&
            runCatching { existing.checkValidity(); existing.verify(publicKey) }.isSuccess) return@synchronized existing
        val certificate = CertificateIssuer.root(publicKey, store.getKey(ALIAS, null) as PrivateKey, System.currentTimeMillis())
        val output = file.startWrite()
        try { output.write(certificate.encoded); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
        certificate
    }

    fun isInstalled(): Boolean {
        val expected = root().encoded
        val trust = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        return trust.aliases().toList().any { alias ->
            trust.getCertificate(alias)?.encoded?.contentEquals(expected) == true
        }
    }

    fun serverContext(): SSLContext {
        val root = root()
        val leaf = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val certificate = CertificateIssuer.leaf(root, keyStore().getKey(ALIAS, null) as PrivateKey,
            leaf.public, System.currentTimeMillis())
        val manager = object : X509ExtendedKeyManager() {
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = null
            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = null
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) =
                if (keyType?.startsWith("RSA") == true) arrayOf("capture") else null
            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) =
                getServerAliases(keyType, issuers)?.firstOrNull()
            override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) =
                getServerAliases(keyType, issuers)?.firstOrNull()
            override fun getCertificateChain(alias: String?) = arrayOf(certificate, root)
            override fun getPrivateKey(alias: String?) = leaf.private
        }
        return SSLContext.getInstance("TLS").apply { init(arrayOf(manager), null, null) }
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    companion object {
        private const val ALIAS = "ibudaoa.diagnostic.ca.v1"
        private val lock = Any()
    }
}
