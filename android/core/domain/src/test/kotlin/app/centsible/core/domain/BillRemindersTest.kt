package app.centsible.core.domain

import app.centsible.core.model.AmountOp
import app.centsible.core.model.Money
import app.centsible.core.model.Schedule
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class BillRemindersTest {
    private val today = LocalDate.parse("2026-09-28")

    private fun schedule(id: String, name: String?, vararg dates: String, completed: Boolean = false) = Schedule(
        id, name, dates.firstOrNull(), completed, false, null, null, Money(-150000), null, AmountOp.Is, null, dates.firstOrNull(), dates.toList(),
    )

    @Test fun `only bills inside the lead time`() {
        val due = BillReminders.due(
            listOf(schedule("rent", "Rent", "2026-10-01"), schedule("net", "Netflix", "2026-09-29"), schedule("old", "Gym", "2026-09-27")),
            today, daysAhead = 1, alreadySent = emptySet(),
        ) { null }
        assertEquals(listOf("Netflix"), due.map { it.title })
        assertEquals("due tomorrow", due.single().whenText)
    }

    @Test fun `each due date is announced once`() {
        val s = schedule("rent", "Rent", "2026-09-28", "2026-10-28")
        val first = BillReminders.due(listOf(s), today, 3, emptySet()) { null }
        assertEquals(listOf("rent@2026-09-28"), first.map { it.key })
        assertEquals(emptyList<BillReminder>(), BillReminders.due(listOf(s), today, 3, first.map { it.key }.toSet()) { null })
    }

    @Test fun `unnamed schedules use the payee, completed ones are skipped`() {
        val due = BillReminders.due(
            listOf(schedule("a", null, "2026-09-28"), schedule("b", "Done", "2026-09-28", completed = true)),
            today, 0, emptySet(),
        ) { "Oak Street Apartments" }
        assertEquals(listOf("Oak Street Apartments"), due.map { it.title })
        assertEquals("due today", due.single().whenText)
    }

    @Test fun `old sent keys are dropped`() {
        val kept = BillReminders.prune(setOf("a@2026-09-01", "b@2026-09-20", "junk"), today)
        assertEquals(setOf("b@2026-09-20"), kept)
    }

    private fun alert(id: String, severity: app.centsible.core.model.Insight.Severity) = app.centsible.core.model.Insight(
        id, app.centsible.core.model.Insight.Kind.CategoryPace, severity, "t", "d", app.centsible.core.model.Money.Zero, "2026-09-20", null, null, null,
    )

    @Test fun `spending alerts are sent once, warnings only, and remembered for weeks`() {
        val today = java.time.LocalDate.of(2026, 9, 20)
        val alerts = listOf(alert("pace:food", app.centsible.core.model.Insight.Severity.Warning), alert("pace:gas", app.centsible.core.model.Insight.Severity.Good))
        val first = BillReminders.newAlerts(alerts, emptySet())
        org.junit.Assert.assertEquals(listOf("pace:food"), first.map { it.id })
        val sent = first.map { BillReminders.alertKey(it, today) }.toSet()
        org.junit.Assert.assertEquals(emptyList<String>(), BillReminders.newAlerts(alerts, sent).map { it.id })
        // Still remembered a month later (a pace alert can stay true all month), gone after 45 days.
        org.junit.Assert.assertEquals(sent, BillReminders.prune(sent, today.plusDays(30)))
        org.junit.Assert.assertEquals(emptySet<String>(), BillReminders.prune(sent, today.plusDays(46)))
    }
}
