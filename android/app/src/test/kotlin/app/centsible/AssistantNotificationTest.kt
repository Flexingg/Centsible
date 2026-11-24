package app.centsible

import org.junit.Assert.assertEquals
import org.junit.Test

class AssistantNotificationTest {
    @Test fun `a reply reads as plain text in a notification`() {
        val md = "## Groceries\n\nYou have **$64.30** left in `Groceries`.\n\n- Costco: $40\n- [Details](https://x.example)\n\n| a | b |\n|---|---|\n| 1 | 2 |"
        assertEquals("Groceries You have $64.30 left in Groceries. Costco: $40 Details a · b 1 · 2", AssistantBackground.plain(md))
    }
}
