package app.centsible.core.data

import app.centsible.core.domain.ChartAccent
import app.centsible.core.domain.ChartKind
import app.centsible.core.domain.ChartSeries
import app.centsible.core.domain.ChartStyle
import app.centsible.core.domain.ChartWidgetConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class ChartConfigCodecTest {
    @Test
    fun `round-trips full custom chart config`() {
        val original = ChartWidgetConfig(
            kind = ChartKind.Monthly,
            title = "My Income Trend",
            style = ChartStyle.Area,
            accent = ChartAccent.Green,
            series = ChartSeries.Income,
            months = 12,
            showReference = false,
            showComparison = false,
            showLegend = false,
            categoryIds = listOf("c1", "c2"),
        )
        val decoded = ChartConfigCodec.decode(ChartConfigCodec.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `decodes legacy pipe-delimited widget config`() {
        val decoded = ChartConfigCodec.decode("category|c1|Groceries")
        assertEquals(ChartWidgetConfig(ChartKind.Category, "c1", "Groceries"), decoded)
    }
}
