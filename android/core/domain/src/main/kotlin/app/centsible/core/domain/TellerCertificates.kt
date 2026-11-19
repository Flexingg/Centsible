package app.centsible.core.domain

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Sorts the files someone picked into Teller's certificate and private key, by what's in
 * them (PEM headers) rather than their names: the dashboard's download, a .zip of both, or
 * the two .pem files picked together all work.
 */
object TellerCertificates {
    data class Found(val certificate: String?, val privateKey: String?, val problem: String?) {
        val complete get() = certificate != null && privateKey != null
    }

    private const val MAX_BYTES = 1_000_000

    fun extract(files: List<Pair<String, ByteArray>>): Found {
        var cert: String? = null
        var key: String? = null
        val texts = files.flatMap { (name, bytes) -> if (isZip(name, bytes)) unzip(bytes) else listOf(bytes) }
        for (bytes in texts) {
            if (bytes.size > MAX_BYTES) continue
            val text = bytes.toString(Charsets.UTF_8)
            pem(text, "CERTIFICATE")?.let { cert = cert ?: it }
            Regex("-----BEGIN ((?:RSA |EC |ENCRYPTED )?PRIVATE KEY)-----").find(text)?.groupValues?.get(1)?.let { label ->
                if (label == "ENCRYPTED PRIVATE KEY") return Found(cert, key, "That private key is password-protected. Use the unprotected private_key.pem Teller gives you.")
                pem(text, label)?.let { key = key ?: it }
            }
        }
        val problem = when {
            cert == null && key == null -> "Those files aren't a Teller certificate. Pick the .zip from the Teller dashboard, or certificate.pem and private_key.pem together."
            cert == null -> "Found the private key but not the certificate. Pick certificate.pem too."
            key == null -> "Found the certificate but not the private key. Pick private_key.pem too."
            else -> null
        }
        return Found(cert, key, problem)
    }

    private fun pem(text: String, label: String): String? {
        val begin = "-----BEGIN $label-----"
        val end = "-----END $label-----"
        val start = text.indexOf(begin).takeIf { it >= 0 } ?: return null
        val stop = text.indexOf(end, start).takeIf { it >= 0 } ?: return null
        return text.substring(start, stop + end.length).trim() + "\n"
    }

    private fun isZip(name: String, bytes: ByteArray) =
        name.endsWith(".zip", ignoreCase = true) || (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() && bytes[2] == 3.toByte() && bytes[3] == 4.toByte())

    private fun unzip(bytes: ByteArray): List<ByteArray> = runCatching {
        val out = mutableListOf<ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            var n = 0
            while (entry != null && n < 20) {
                if (!entry.isDirectory) out += zip.readNBytes(MAX_BYTES + 1)
                entry = zip.nextEntry
                n++
            }
        }
        out
    }.getOrDefault(emptyList())
}
