import app.canopy.core.designsystem.component.CategoryEmoji
import app.canopy.core.designsystem.component.MoneyFormat
import app.canopy.core.model.Money
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class MoneyFormatTest {
    @Test
    fun `formats cents with sign`() {
        assertEquals("$1,234.56", MoneyFormat.format(Money(123_456), locale = Locale.US))
        assertEquals("-$45.23", MoneyFormat.format(Money(-4_523), locale = Locale.US))
        assertEquals("+$10.00", MoneyFormat.format(Money(1_000), signed = true, locale = Locale.US))
        assertEquals("$1,235", MoneyFormat.format(Money(123_456), showCents = false, locale = Locale.US))
    }

    @Test
    fun `picks category emoji by keyword or keeps the user's own`() {
        assertEquals("🛒", CategoryEmoji.forName("Groceries"))
        assertEquals("⛽", CategoryEmoji.forName("Gas"))
        assertEquals("🎸", CategoryEmoji.forName("🎸 Music lessons"))
        assertEquals("🏷️", CategoryEmoji.forName("Zzz"))
    }
}

class MoneyInputTest {
    @Test
    fun `parses typed amounts`() {
        assertEquals(Money(123_450), app.canopy.core.designsystem.component.MoneyInput.parse("1,234.5"))
        assertEquals(Money(500), app.canopy.core.designsystem.component.MoneyInput.parse("$5"))
        assertEquals(null, app.canopy.core.designsystem.component.MoneyInput.parse("5.123"))
        assertEquals(null, app.canopy.core.designsystem.component.MoneyInput.parse("abc"))
        assertEquals("1234.50", app.canopy.core.designsystem.component.MoneyInput.toInput(Money(123_450)))
    }
}
