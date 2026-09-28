package app.centsible.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BridgeUrlsTest {
    @Test fun `adds https to a bare host`() = assertEquals("https://budget-api.example.com", BridgeUrls.normalize(" budget-api.example.com/ "))
    @Test fun `keeps an explicit scheme`() = assertEquals("https://x.example.com", BridgeUrls.normalize("HTTPS://x.example.com"))
    @Test fun `keeps a local http address`() = assertEquals("http://192.168.1.20:8787", BridgeUrls.normalize("http://192.168.1.20:8787"))
    @Test fun `refuses plain http on the internet`() = assertNull(BridgeUrls.normalize("http://budget-api.example.com"))
    @Test fun `refuses nonsense`() = assertNull(BridgeUrls.normalize("   "))
}
