package app.centsible.core.domain

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TellerCertificatesTest {
    private val cert = "-----BEGIN CERTIFICATE-----\nMIIBcert\n-----END CERTIFICATE-----\n"
    private val key = "-----BEGIN PRIVATE KEY-----\nMIIEkey\n-----END PRIVATE KEY-----\n"

    private fun zip(vararg files: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> files.forEach { (n, t) -> z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() } }
        return out.toByteArray()
    }

    @Test
    fun `reads both from Teller's zip, whatever it calls them`() {
        val found = TellerCertificates.extract(listOf("teller.zip" to zip("teller/a.pem" to key, "teller/b.pem" to cert)))
        assertTrue(found.complete)
        assertEquals(cert, found.certificate)
        assertEquals(key, found.privateKey)
        assertNull(found.problem)
    }

    @Test
    fun `reads two pem files picked together, in either order`() {
        val found = TellerCertificates.extract(listOf("private_key.pem" to key.toByteArray(), "certificate.pem" to ("junk before\n" + cert).toByteArray()))
        assertEquals(cert, found.certificate)
        assertEquals(key, found.privateKey)
    }

    @Test
    fun `says what is missing`() {
        assertTrue(TellerCertificates.extract(listOf("certificate.pem" to cert.toByteArray())).problem!!.contains("private_key.pem"))
        assertTrue(TellerCertificates.extract(listOf("private_key.pem" to key.toByteArray())).problem!!.contains("certificate.pem"))
        assertTrue(TellerCertificates.extract(listOf("photo.jpg" to byteArrayOf(1, 2, 3))).problem!!.contains("aren't a Teller certificate"))
        val rsa = "-----BEGIN RSA PRIVATE KEY-----\nabc\n-----END RSA PRIVATE KEY-----"
        assertEquals("$rsa\n", TellerCertificates.extract(listOf("k.pem" to rsa.toByteArray(), "c.pem" to cert.toByteArray())).privateKey)
        val enc = "-----BEGIN ENCRYPTED PRIVATE KEY-----\nabc\n-----END ENCRYPTED PRIVATE KEY-----"
        assertTrue(TellerCertificates.extract(listOf("k.pem" to enc.toByteArray())).problem!!.contains("password-protected"))
    }
}
