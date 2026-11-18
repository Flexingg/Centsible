package app.centsible.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppLinksTest {
    @Test
    fun `opens named screens`() {
        assertEquals(AppLink.To(AppLink.Screen.Review), AppLinks.parse("centsible://open/review"))
        assertEquals(AppLink.To(AppLink.Screen.BankSync), AppLinks.parse(" centsible://open/bank-sync/ "))
        assertEquals(AppLink.To(AppLink.Screen.Teller), AppLinks.parse("centsible://open/teller"))
        assertEquals(AppLink.To(AppLink.Screen.NetWorth), AppLinks.parse("CENTSIBLE://open/Net-Worth"))
    }

    @Test
    fun `opens an account`() {
        assertEquals(
            AppLink.Account("07e0d317-827e-472c-8077-89a781ba2e9c"),
            AppLinks.parse("centsible://open/account/07e0d317-827e-472c-8077-89a781ba2e9c"),
        )
    }

    @Test
    fun `opens a category's transactions, optionally for one month`() {
        assertEquals(
            AppLink.Category("4b904f31", "Eating Out", "2026-11"),
            AppLinks.parse("centsible://open/category/4b904f31?name=Eating%20Out&month=2026-11"),
        )
        assertEquals(AppLink.Category("4b904f31", "Category", null), AppLinks.parse("centsible://open/category/4b904f31?month=2026-13"))
    }

    @Test
    fun `ignores anything else`() {
        assertNull(AppLinks.parse("https://open/review"))
        assertNull(AppLinks.parse("centsible://pair/review"))
        assertNull(AppLinks.parse("centsible://open/settings"))
        assertNull(AppLinks.parse("centsible://open/account/../server"))
        assertNull(AppLinks.parse("centsible://open/account/a%2Fb"))
        assertNull(AppLinks.parse("centsible://open/account"))
        assertNull(AppLinks.parse("not a link"))
    }
}
