package app.centsible.feature.planning

import app.centsible.core.model.DeductionKind
import app.centsible.core.model.DeductionMode
import app.centsible.core.model.LearnedDeduction
import app.centsible.core.model.LearnedTemplate
import app.centsible.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomeCalcTest {
    @Test
    fun `entering hours with a learned template calculates gross and percent deductions while keeping fixed flat`() {
        val template = LearnedTemplate(
            hourlyRate = Money(5000), // $50/hr
            employer = "Acme Corp",
            deductions = listOf(
                LearnedDeduction("401(k)", DeductionKind.Retirement, DeductionMode.Percent, Money(24000), rate = 6.0),
                LearnedDeduction("Federal Tax", DeductionKind.Tax, DeductionMode.Percent, Money(60000), rate = 15.0),
                LearnedDeduction("Health Insurance", DeductionKind.Benefit, DeductionMode.Fixed, Money(12000), rate = 0.0),
            ),
        )
        val initial = IncomeCalc.fromTemplate(template, today = "2026-06-15")
        assertEquals("Acme Corp", initial.employer)
        assertEquals("50.00", initial.hourlyRateText)

        // Enter 80 hours -> $4,000 gross -> 401(k) $240, Fed Tax $600, Health $120 -> Net $3,040
        val after80 = IncomeCalc.updateHoursAndRate(initial, hoursText = "80")
        assertEquals(Money(400000), after80.gross)
        assertEquals(Money(24000), after80.deductions[0].amount)
        assertEquals(Money(60000), after80.deductions[1].amount)
        assertEquals(Money(12000), after80.deductions[2].amount)
        assertEquals(Money(304000), after80.net)
        assertTrue(after80.isValid)

        // Change to 90 hours -> $4,500 gross -> 401(k) $270, Fed Tax $675, Health stays $120!
        val after90 = IncomeCalc.updateHoursAndRate(after80, hoursText = "90")
        assertEquals(Money(450000), after90.gross)
        assertEquals(Money(27000), after90.deductions[0].amount)
        assertEquals(Money(67500), after90.deductions[1].amount)
        assertEquals(Money(12000), after90.deductions[2].amount)
        assertEquals(Money(343500), after90.net)
    }

    @Test
    fun `updating gross directly recalculates percent deductions only`() {
        val form = PayStubFormState(
            date = "2026-06-15",
            grossText = "2000",
            deductions = listOf(
                DeductionDraft("401(k)", DeductionKind.Retirement, DeductionMode.Percent, "100.00", "5"),
                DeductionDraft("Dental", DeductionKind.Benefit, DeductionMode.Fixed, "25.00", ""),
            ),
        )
        val updated = IncomeCalc.updateGross(form, "3000")
        assertEquals(Money(300000), updated.gross)
        assertEquals(Money(15000), updated.deductions[0].amount)
        assertEquals(Money(2500), updated.deductions[1].amount)
        assertEquals(Money(282500), updated.net)
    }
}
