package io.github.ibudaoa.capture

import java.security.MessageDigest

/** Public UI state only; no credential, private key, or private URI. */
enum class CertificateSetupPhase { IDLE, CHECKING, SAVING, WAITING_INSTALL, NOT_INSTALLED, INSTALLED, CHECK_FAILED, SAVE_FAILED }

internal fun needsCertificateSetup(phase: CertificateSetupPhase, captured: Boolean, trusted: Boolean, capturing: Boolean): Boolean =
    !capturing && (phase !in setOf(CertificateSetupPhase.IDLE, CertificateSetupPhase.INSTALLED) || (!captured && !trusted))

internal data class CertificateDownload(val uri: String, val fingerprint: String, val fileName: String)

internal interface CertificateDownloadStorage {
    fun reference(): CertificateDownload?
    fun read(uri: String): ByteArray?
    fun publish(bytes: ByteArray, fingerprint: String): CertificateDownload
    fun remember(file: CertificateDownload)
    fun remove(uri: String)
}

/** Only public DER bytes enter this component. Revalidate the actual file before reusing it. */
internal class CertificateDownloadRepository(private val storage: CertificateDownloadStorage) {
    @Synchronized fun existing(bytes: ByteArray): CertificateDownload? {
        require(bytes.size in 1..65_536)
        val fingerprint = fingerprint(bytes)
        val previous = storage.reference()
        return previous?.takeIf { it.fileName.endsWith(".crt") && it.fingerprint == fingerprint && storage.read(it.uri)?.contentEquals(bytes) == true }
    }

    private fun fingerprint(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    @Synchronized fun ensure(bytes: ByteArray): CertificateDownload {
        existing(bytes)?.let { return it }
        val fingerprint = fingerprint(bytes)
        val file = storage.publish(bytes, fingerprint)
        try { storage.remember(file) }
        catch (error: Exception) {
            runCatching { storage.remove(file.uri) }
            throw error
        }
        return file
    }
}

/** One explicit setup attempt may continue to VPN once. Cancellation fences late IO results. */
internal class CertificateSetupGate {
    private var generation = 0L
    private var active = false
    private var waiting = false
    private var continued = false
    @Synchronized fun begin(): Long { generation++; active = true; waiting = false; continued = false; return generation }
    @Synchronized fun current(): Long = generation
    @Synchronized fun accepts(id: Long): Boolean = active && id == generation
    @Synchronized fun waiting(id: Long): Boolean {
        if (!accepts(id) || continued) return false
        waiting = true
        return true
    }
    @Synchronized fun installed(id: Long): Boolean {
        if (!accepts(id) || continued) return false
        continued = true; waiting = false
        return true
    }
    @Synchronized fun returned(id: Long, trusted: Boolean): Boolean = waiting && trusted && installed(id)
    @Synchronized fun cancel() { generation++; active = false; waiting = false }
}
