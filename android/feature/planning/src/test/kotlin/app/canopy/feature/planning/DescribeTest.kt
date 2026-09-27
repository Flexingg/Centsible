package app.canopy.feature.planning

import app.canopy.core.model.Frequency
import app.canopy.core.model.Recurrence
import app.canopy.core.testing.SamplePlanning
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class DescribeTest {
    private val names = Describe.Names(SamplePlanning.payeeNames, mapOf("c-groceries" to "Groceries", "c-kids" to "Kids"), emptyMap())

    @Test
    fun `describes recurrences like a person would`() {
        assertEquals("Monthly on the 21st", Describe.recurrence(Recurrence(Frequency.Monthly, 1, "2026-09-21"), null))
        assertEquals("Every 2 weeks", Describe.recurrence(Recurrence(Frequency.Weekly, 2, "2026-09-11"), null))
        assertEquals("Weekly on Friday", Describe.recurrence(Recurrence(Frequency.Weekly, 1, "2026-09-11"), null))
        assertEquals("Yearly on Mar 3", Describe.recurrence(Recurrence(Frequency.Yearly, 1, "2026-03-03"), null))
        assertEquals("Once on Oct 1", Describe.recurrence(null, "2026-10-01"))
        assertEquals("Monthly on the 1st (custom pattern)", Describe.recurrence(Recurrence(Frequency.Monthly, 1, "2026-01-01", patternsJson = "[{}]"), null))
    }

    @Test
    fun `describes due dates relative to today`() {
        val today = LocalDate.of(2026, 9, 27)
        assertEquals("Today", Describe.due("2026-09-27", today))
        assertEquals("Tomorrow", Describe.due("2026-09-28", today))
        assertEquals("In 3 days", Describe.due("2026-09-30", today))
        assertEquals("Oct 20", Describe.due("2026-10-20", today))
        assertEquals("2d overdue", Describe.due("2026-09-25", today))
    }

    @Test
    fun `describes rules in plain language with names`() {
        val (ifText, thenText) = Describe.rule(SamplePlanning.rules[0], names)
        assertEquals("If merchant is Trader Joe's", ifText)
        assertEquals("Set category to Groceries", thenText)
        val (if2, _) = Describe.rule(SamplePlanning.rules[1], names)
        assertEquals("If bank description contains \"AMZN\" or bank description contains \"Amazon\"", if2)
        val (if3, then3) = Describe.rule(SamplePlanning.rules[2], names)
        assertEquals("If notes contains \"#kids\" and amount is less than -$50.00", if3)
        assertEquals("Set category to Kids, add \" (reviewed)\" to the end of notes", then3)
    }
}
