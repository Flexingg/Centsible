package app.centsible.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingLinksTest {
    @Test
    fun `parses bridge url, code and Cloudflare Access token`() {
        val link = PairingLinks.parse(
            "actualbridge://pair?u=https%3A%2F%2Fbudget-api.example.com&c=YWJ5-P8PK&cfid=abc.access&cfsecret=s3cr%2Bt",
        )!!
        assertEquals("https://budget-api.example.com", link.bridgeUrl)
        assertEquals("YWJ5-P8PK", link.code)
        assertEquals("abc.access", link.cfAccessClientId)
        assertEquals("s3cr+t", link.cfAccessClientSecret)
    }

    @Test
    fun `rejects other schemes and plain http on the internet`() {
        assertNull(PairingLinks.parse("https://evil.example.com/pair?u=x&c=y"))
        assertNull(PairingLinks.parse("actualbridge://pair?u=http%3A%2F%2Fexample.com&c=ABCD-EFGH"))
        assertNull(PairingLinks.parse("actualbridge://pair?c=ABCD-EFGH"))
    }

    @Test
    fun `allows http on a LAN for development`() {
        assertEquals("http://192.168.1.20:8787", PairingLinks.parse("actualbridge://pair?u=http%3A%2F%2F192.168.1.20%3A8787&c=A")!!.bridgeUrl)
    }
}
