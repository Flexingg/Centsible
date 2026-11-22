package app.centsible.feature.planning

import app.centsible.core.designsystem.component.MoneyInput
import app.centsible.core.model.DeductionKind
import app.centsible.core.model.DeductionMode
import app.centsible.core.model.LearnedTemplate
import app.centsible.core.model.Money
import app.centsible.core.model.PayStub
import app.centsible.core.model.PayStubDeduction
import app.centsible.core.model.PayStubInput
import java.time.LocalDate
import kotlin.math.roundToLong

data class DeductionDraft(
    val name: String,
    val kind: DeductionKind,
    val mode: DeductionMode,
    /** Dollar text (e.g. "120.00"), always kept in sync with the computed/entered dollar amount. */
    val amountText: String,
    /** Percent text (e.g. "6.0"), used when [mode] is [DeductionMode.Percent]. */
    val rateText: String,
) {
    val amount: Money get() = MoneyInput.parse(amountText)?.takeIf { !it.isNegative } ?: Money.Zero
    val rate: Double? get() = rateText.trim().toDoubleOrNull()?.takeIf { it >= 0.0 && it <= 100.0 }
}

data class PayStubFormState(
    val id: String? = null,
    val date: String = LocalDate.now().toString(),
    val employer: String = "",
    val hoursText: String = "",
    val hourlyRateText: String = "",
    val grossText: String = "",
    val deductions: List<DeductionDraft> = emptyList(),
    val notes: String = "",
) {
    val hours: Double? get() = hoursText.trim().toDoubleOrNull()?.takeIf { it > 0.0 }
    val hourlyRate: Money? get() = MoneyInput.parse(hourlyRateText)?.takeIf { it.minor > 0 }
    val gross: Money get() = MoneyInput.parse(grossText)?.takeIf { !it.isNegative } ?: Money.Zero
    val totalDeductions: Money get() = Money(deductions.sumOf { it.amount.minor })
    val net: Money get() = Money(gross.minor - totalDeductions.minor)
    val takeHomePercent: Double get() = if (gross.minor > 0) (net.minor.toDouble() / gross.minor * 100.0) else 0.0
    val isValid: Boolean get() = Regex("""^\d{4}-\d{2}-\d{2}$""").matches(date.trim()) && gross.minor > 0 && deductions.all { it.name.isNotBlank() }
}

data class DeductionPreset(val name: String, val kind: DeductionKind, val defaultMode: DeductionMode)

object IncomeCalc {
    val presets: List<DeductionPreset> = listOf(
        DeductionPreset("401(k)", DeductionKind.Retirement, DeductionMode.Percent),
        DeductionPreset("Federal Tax", DeductionKind.Tax, DeductionMode.Percent),
        DeductionPreset("State Tax", DeductionKind.Tax, DeductionMode.Percent),
        DeductionPreset("Social Security", DeductionKind.Tax, DeductionMode.Percent),
        DeductionPreset("Medicare", DeductionKind.Tax, DeductionMode.Percent),
        DeductionPreset("Health Insurance", DeductionKind.Benefit, DeductionMode.Fixed),
        DeductionPreset("HSA", DeductionKind.Benefit, DeductionMode.Fixed),
        DeductionPreset("Dental / Vision", DeductionKind.Benefit, DeductionMode.Fixed),
    )

    fun formatPercent(p: Double): String =
        if (p % 1.0 == 0.0) p.toLong().toString()
        else String.format(java.util.Locale.US, "%.2f", p).trimEnd('0').trimEnd('.')

    fun moneyToText(m: Money): String = if (m.minor == 0L) "" else MoneyInput.toInput(m)

    /** Builds a pre-filled form for a brand new pay stub using the bridge's learned template. */
    fun fromTemplate(template: LearnedTemplate, today: String = LocalDate.now().toString()): PayStubFormState {
        val rows = template.deductions.map { d ->
            DeductionDraft(
                name = d.name,
                kind = d.kind,
                mode = d.mode,
                amountText = if (d.mode == DeductionMode.Fixed) moneyToText(d.amount) else "",
                rateText = if (d.mode == DeductionMode.Percent) formatPercent(d.rate) else "",
            )
        }
        return PayStubFormState(
            id = null,
            date = today,
            employer = template.employer.orEmpty(),
            hourlyRateText = template.hourlyRate?.let(::moneyToText).orEmpty(),
            deductions = rows,
        )
    }

    /** Loads an existing pay stub into the editor form. */
    fun fromStub(stub: PayStub): PayStubFormState {
        val rows = stub.deductions.map { d ->
            val mode = d.mode ?: if (d.kind == DeductionKind.Tax || d.kind == DeductionKind.Retirement) DeductionMode.Percent else DeductionMode.Fixed
            val computedRate = d.rate ?: if (stub.gross.minor > 0) (d.amount.minor.toDouble() / stub.gross.minor * 100.0) else 0.0
            DeductionDraft(
                name = d.name,
                kind = d.kind,
                mode = mode,
                amountText = moneyToText(d.amount),
                rateText = if (mode == DeductionMode.Percent) formatPercent(computedRate) else "",
            )
        }
        val derivedRate = stub.hourlyRate ?: if (stub.hours != null && stub.hours > 0.0 && stub.gross.minor > 0) {
            Money((stub.gross.minor / stub.hours).roundToLong())
        } else {
            null
        }
        return PayStubFormState(
            id = stub.id,
            date = stub.date,
            employer = stub.employer.orEmpty(),
            hoursText = stub.hours?.let(::formatPercent).orEmpty(),
            hourlyRateText = derivedRate?.let(::moneyToText).orEmpty(),
            grossText = moneyToText(stub.gross),
            deductions = rows,
            notes = stub.notes.orEmpty(),
        )
    }

    /** Updates hours (or hourly rate): auto-calculates gross pay and recalculates %-based deductions. */
    fun updateHoursAndRate(form: PayStubFormState, hoursText: String = form.hoursText, hourlyRateText: String = form.hourlyRateText): PayStubFormState {
        val updated = form.copy(hoursText = hoursText, hourlyRateText = hourlyRateText)
        val h = updated.hours
        val r = updated.hourlyRate
        if (h != null && r != null) {
            val newGross = Money((h * r.minor).roundToLong())
            return updated.copy(
                grossText = moneyToText(newGross),
                deductions = recalculateDeductions(newGross, updated.deductions),
            )
        }
        return updated
    }

    /** Updates gross pay directly and recalculates all %-based deductions while leaving Fixed rows unchanged. */
    fun updateGross(form: PayStubFormState, grossText: String): PayStubFormState {
        val updated = form.copy(grossText = grossText)
        return updated.copy(deductions = recalculateDeductions(updated.gross, updated.deductions))
    }

    /** Recalculates dollar amounts for every [DeductionMode.Percent] row based on [gross]. */
    fun recalculateDeductions(gross: Money, rows: List<DeductionDraft>): List<DeductionDraft> = rows.map { row ->
        if (row.mode == DeductionMode.Percent) {
            val r = row.rate
            if (r != null && gross.minor > 0) {
                val cents = (gross.minor * r / 100.0).roundToLong().coerceAtLeast(0L)
                row.copy(amountText = moneyToText(Money(cents)))
            } else {
                row.copy(amountText = "")
            }
        } else {
            row
        }
    }

    /** Updates one deduction row, keeping amount/rate consistent with [gross]. */
    fun updateDeduction(form: PayStubFormState, index: Int, transform: (DeductionDraft) -> DeductionDraft): PayStubFormState {
        if (index !in form.deductions.indices) return form
        val prev = form.deductions[index]
        var next = transform(prev)
        val gross = form.gross
        if (next.mode != prev.mode) {
            next = if (next.mode == DeductionMode.Percent) {
                val pct = if (gross.minor > 0 && next.amount.minor > 0) next.amount.minor.toDouble() / gross.minor * 100.0 else (next.rate ?: 0.0)
                val rText = if (pct > 0.0) formatPercent(pct) else next.rateText
                next.copy(rateText = rText)
            } else {
                next
            }
        } else if (next.mode == DeductionMode.Percent && next.rateText != prev.rateText) {
            val r = next.rate
            val amt = if (r != null && gross.minor > 0) moneyToText(Money((gross.minor * r / 100.0).roundToLong())) else ""
            next = next.copy(amountText = amt)
        }
        val list = form.deductions.toMutableList()
        list[index] = next
        return form.copy(deductions = list)
    }

    fun toInput(form: PayStubFormState): PayStubInput = PayStubInput(
        date = form.date.trim(),
        employer = form.employer.trim().ifEmpty { null },
        hours = form.hours,
        hourlyRate = form.hourlyRate,
        gross = form.gross,
        deductions = form.deductions
            .filter { it.name.isNotBlank() }
            .map { d ->
                PayStubDeduction(
                    name = d.name.trim(),
                    kind = d.kind,
                    amount = d.amount,
                    mode = d.mode,
                    rate = if (d.mode == DeductionMode.Percent) d.rate else null,
                )
            },
        notes = form.notes.trim().ifEmpty { null },
    )
}
