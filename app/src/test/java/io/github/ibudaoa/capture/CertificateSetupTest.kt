package io.github.ibudaoa.capture

import org.junit.Assert.*
import org.junit.Test

class CertificateSetupTest {
    private class Downloads : CertificateDownloadStorage {
        var ref: CertificateDownload? = null
        val files = mutableMapOf<String, ByteArray>()
        var writes = 0
        var failPublish = false
        var failRemember = false
        override fun reference() = ref
        override fun read(uri: String) = files[uri]
        override fun publish(bytes: ByteArray, fingerprint: String): CertificateDownload {
            writes++
            if (failPublish) error("Fake disk failure")
            val uri = "file-$writes"
            files[uri] = bytes.clone()
            return CertificateDownload(uri, fingerprint, "certificate-$writes.cer.crt")
        }
        override fun remember(file: CertificateDownload) { if (failRemember) error("Fake metadata failure"); ref = file }
        override fun remove(uri: String) { files.remove(uri) }
    }

    @Test fun `valid export is reused across repository recreation`() {
        val store = Downloads()
        val first = CertificateDownloadRepository(store).ensure(byteArrayOf(1, 2, 3))
        val restored = CertificateDownloadRepository(store)
        assertEquals(first, restored.existing(byteArrayOf(1, 2, 3)))
        assertEquals(first, restored.ensure(byteArrayOf(1, 2, 3)))
        assertEquals(1, store.writes)
        assertEquals("certificate-1.cer.crt", first.fileName)
    }

    @Test fun `missing file is recreated and actual filename returned`() {
        val store = Downloads(); val repo = CertificateDownloadRepository(store)
        val old = repo.ensure(byteArrayOf(1)); store.files.clear()
        assertNull(repo.existing(byteArrayOf(1)))
        val replacement = repo.ensure(byteArrayOf(1))
        assertNotEquals(old.uri, replacement.uri)
        assertEquals("certificate-2.cer.crt", replacement.fileName)
    }

    @Test fun `legacy filename is replaced without deleting old download`() {
        val store = Downloads(); val repo = CertificateDownloadRepository(store)
        val old = repo.ensure(byteArrayOf(1))
        store.ref = old.copy(fileName = "ibudaoa-连接证书.cer")
        assertNull(repo.existing(byteArrayOf(1)))
        val replacement = repo.ensure(byteArrayOf(1))
        assertNotEquals(old.uri, replacement.uri)
        assertTrue(store.files.containsKey(old.uri))
        assertEquals(replacement, repo.ensure(byteArrayOf(1)))
    }

    @Test fun `modified contents or changed root cannot reuse stale file`() {
        val store = Downloads(); val repo = CertificateDownloadRepository(store)
        val old = repo.ensure(byteArrayOf(1)); store.files[old.uri] = byteArrayOf(9)
        assertNotEquals(old.uri, repo.ensure(byteArrayOf(1)).uri)
        val second = store.ref!!
        assertNotEquals(second.fingerprint, repo.ensure(byteArrayOf(2)).fingerprint)
    }

    @Test fun `failed save does not remember export and can retry`() {
        val store = Downloads(); val repo = CertificateDownloadRepository(store)
        store.failPublish = true
        assertTrue(runCatching { repo.ensure(byteArrayOf(1)) }.isFailure)
        assertNull(store.ref)
        store.failPublish = false
        repo.ensure(byteArrayOf(1))
        assertNotNull(store.ref)
    }

    @Test fun `metadata failure removes only newly created file and keeps previous reference`() {
        val store = Downloads(); val repo = CertificateDownloadRepository(store)
        val previous = repo.ensure(byteArrayOf(1)); store.failRemember = true
        assertTrue(runCatching { repo.ensure(byteArrayOf(2)) }.isFailure)
        assertEquals(previous, store.ref)
        assertEquals(setOf(previous.uri), store.files.keys)
    }

    @Test fun `concurrent export requests publish once`() {
        val store = Downloads(); val repo = CertificateDownloadRepository(store)
        val threads = List(8) { Thread { repo.ensure(byteArrayOf(1, 2, 3)) } }
        threads.forEach(Thread::start); threads.forEach(Thread::join)
        assertEquals(1, store.writes)
    }

    @Test fun `oversized and empty files never reach storage`() {
        val store = Downloads(); val repo = CertificateDownloadRepository(store)
        assertTrue(runCatching { repo.ensure(byteArrayOf()) }.isFailure)
        assertTrue(runCatching { repo.ensure(ByteArray(65_537)) }.isFailure)
        assertEquals(0, store.writes)
    }

    @Test fun `existing certificate skips waiting and advances once`() {
        val gate = CertificateSetupGate(); val id = gate.begin()
        assertTrue(gate.installed(id))
        assertFalse(gate.installed(id))
        assertFalse(gate.returned(id, true))
    }

    @Test fun `cancelled installation permits later detection but advances only once`() {
        val gate = CertificateSetupGate(); val id = gate.begin()
        assertTrue(gate.waiting(id))
        assertFalse(gate.returned(id, false))
        assertTrue(gate.returned(id, true))
        assertFalse(gate.returned(id, true))
    }

    @Test fun `closing setup fences delayed save and check completions`() {
        val gate = CertificateSetupGate(); val id = gate.begin()
        gate.waiting(id); gate.cancel()
        assertFalse(gate.accepts(id))
        assertFalse(gate.waiting(id))
        assertFalse(gate.returned(id, true))
        assertFalse(gate.installed(id))
    }

    @Test fun `new attempt and process recreation cannot reuse previous continuation grant`() {
        val gate = CertificateSetupGate(); val old = gate.begin(); gate.waiting(old)
        val new = gate.begin()
        assertFalse(gate.returned(old, true))
        assertFalse(gate.returned(new, true))
        assertFalse(CertificateSetupGate().returned(0, true))
        assertTrue(gate.installed(new))
    }

    @Test fun `certificate retry takes precedence over saved school but not active capture`() {
        assertTrue(needsCertificateSetup(CertificateSetupPhase.SAVE_FAILED, true, false, false))
        assertTrue(needsCertificateSetup(CertificateSetupPhase.IDLE, false, false, false))
        assertFalse(needsCertificateSetup(CertificateSetupPhase.WAITING_INSTALL, false, false, true))
        assertFalse(needsCertificateSetup(CertificateSetupPhase.INSTALLED, false, true, false))
    }
}
