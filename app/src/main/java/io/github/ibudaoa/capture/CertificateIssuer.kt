package io.github.ibudaoa.capture

import java.math.BigInteger
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.ASN1Encodable
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERBitString
import org.bouncycastle.asn1.DERNull
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.asn1.x509.ExtensionsGenerator
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.asn1.x509.Time
import org.bouncycastle.asn1.x509.V3TBSCertificateGenerator

object CertificateIssuer {
    private val name = X500Name("CN=ibudaoa Local Diagnostics CA")

    fun root(publicKey: PublicKey, privateKey: PrivateKey, now: Long): X509Certificate =
        issue(name, name, publicKey, privateKey, now, ca = true)

    fun leaf(root: X509Certificate, rootKey: PrivateKey, publicKey: PublicKey, now: Long): X509Certificate =
        issue(X500Name(root.subjectX500Principal.name), X500Name("CN=${ReadOnlyPolicy.HOST}"),
            publicKey, rootKey, now, ca = false)

    private fun issue(issuer: X500Name, subject: X500Name, publicKey: PublicKey,
        signer: PrivateKey, now: Long, ca: Boolean): X509Certificate {
        val algorithm = AlgorithmIdentifier(PKCSObjectIdentifiers.sha256WithRSAEncryption, DERNull.INSTANCE)
        val builder = V3TBSCertificateGenerator().apply {
            setSerialNumber(ASN1Integer(BigInteger(128, SecureRandom()).setBit(127)))
            setSignature(algorithm)
            setIssuer(issuer)
            setSubject(subject)
            setStartDate(Time(Date(now - 86_400_000L)))
            setEndDate(Time(Date(now + if (ca) 180L * 86_400_000 else 7L * 86_400_000)))
            setSubjectPublicKeyInfo(SubjectPublicKeyInfo.getInstance(publicKey.encoded))
        }
        val extensions = ExtensionsGenerator()
        extensions.addExtension(Extension.basicConstraints, true, if (ca) BasicConstraints(0) else BasicConstraints(false))
        extensions.addExtension(Extension.keyUsage, true, KeyUsage(if (ca) KeyUsage.keyCertSign or KeyUsage.cRLSign
            else KeyUsage.digitalSignature or KeyUsage.keyEncipherment))
        if (!ca) {
            extensions.addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth))
            extensions.addExtension(Extension.subjectAlternativeName, false,
                GeneralNames(GeneralName(GeneralName.dNSName, ReadOnlyPolicy.HOST)))
        }
        builder.setExtensions(extensions.generate())
        val tbs = builder.generateTBSCertificate()
        val signature = Signature.getInstance("SHA256withRSA").apply { initSign(signer); update(tbs.encoded) }.sign()
        val encoded = DERSequence(arrayOf<ASN1Encodable>(tbs, algorithm, DERBitString(signature))).encoded
        return CertificateFactory.getInstance("X.509").generateCertificate(encoded.inputStream()) as X509Certificate
    }
}
