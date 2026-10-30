package io.github.ibudaoa.capture

import java.security.KeyPairGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CertificateIssuerTest {
    private fun key() = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    @Test fun `each installation can have an independently verifiable CA`() {
        val one = key(); val two = key(); val now = System.currentTimeMillis()
        val a = CertificateIssuer.root(one.public, one.private, now)
        val b = CertificateIssuer.root(two.public, two.private, now)
        a.verify(one.public); b.verify(two.public)
        assertFalse(a.encoded.contentEquals(b.encoded))
        assertEquals(0, a.basicConstraints)
        assertTrue(a.keyUsage[5])
    }

    @Test fun `leaf is restricted to the exact official host and cannot sign certificates`() {
        val ca = key(); val leafKey = key(); val now = System.currentTimeMillis()
        val root = CertificateIssuer.root(ca.public, ca.private, now)
        val leaf = CertificateIssuer.leaf(root, ca.private, leafKey.public, now)
        leaf.verify(root.publicKey); leaf.checkValidity()
        assertEquals(-1, leaf.basicConstraints)
        assertFalse(leaf.keyUsage.getOrElse(5) { false })
        val names = leaf.subjectAlternativeNames.map { it[1] }
        assertEquals(listOf(ReadOnlyPolicy.HOST), names)
        assertTrue(runCatching { leaf.verify(key().public) }.isFailure)
    }
}
