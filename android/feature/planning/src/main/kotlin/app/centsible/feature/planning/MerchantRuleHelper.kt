package app.centsible.feature.planning

import app.centsible.core.model.CategoryId
import app.centsible.core.model.PayeeId
import app.centsible.core.model.PayeeStat
import app.centsible.core.model.Rule
import app.centsible.core.model.RuleClause
import app.centsible.core.model.RuleDraft
import app.centsible.core.model.RuleValue

/** How a quick rule matches transactions for a merchant. */
enum class MatchMode { Exact, Keyword }

/** Suggested starting match mode, keyword, and optional clean merchant name for a raw bank payee. */
data class MerchantSuggestion(
    val mode: MatchMode,
    val keyword: String,
    val renameTo: String,
)

/**
 * Pure logic for the merchant quick-rules queue: suggesting clean keywords from noisy bank
 * descriptions, detecting whether a merchant is already covered by existing rules, and
 * building Actual-compatible rule drafts.
 */
object MerchantRuleHelper {
    private val prefixRegex = Regex(
        """^(?:TST\*|SQ \*|SP |PP\*|PAYPAL \*|APLPAY |APPLE PAY |POS |DEBIT |ACH |CHECKCARD |PURCHASE |ORIG CO NAME:|WEB ID:)\s*""",
        RegexOption.IGNORE_CASE,
    )
    private val trailingCodeRegex = Regex("""(?:\*[A-Z0-9]{3,}|#\s*\d+|\s+\d{3,}|[-/]\d{2,})\s*$""", RegexOption.IGNORE_CASE)
    private val trailingPunctRegex = Regex("""[\s*\-#/_.:,]+$""")
    private val multiSpaceRegex = Regex("""\s{2,}""")

    /**
     * Inspects a merchant name and decides whether it looks like a noisy bank string
     * (e.g. `TST* TRADER JOE'S #142` or `AMZN MKTP US*2X9F1`). If noisy, defaults to
     * [MatchMode.Keyword] with a stripped keyword and a title-cased rename suggestion.
     */
    fun suggest(rawName: String): MerchantSuggestion {
        val trimmed = rawName.trim()
        if (trimmed.isEmpty()) return MerchantSuggestion(MatchMode.Exact, "", "")

        var cleaned = trimmed
        // Strip up to two stacked prefixes (e.g. "POS DEBIT TST* ...")
        repeat(2) {
            val next = prefixRegex.replace(cleaned, "").trim()
            if (next.length >= 3) cleaned = next
        }
        // Strip trailing store numbers / order hashes (e.g. "*2X9F1", "#142", " 0412")
        repeat(3) {
            val next = trailingCodeRegex.replace(cleaned, "").trim()
            if (next.length >= 3) cleaned = next
        }
        cleaned = trailingPunctRegex.replace(cleaned, "").trim()
        cleaned = multiSpaceRegex.replace(cleaned, " ")

        val stripped = cleaned.length >= 3 && !cleaned.equals(trimmed, ignoreCase = true)
        val isAllCaps = trimmed.length >= 4 && trimmed.any { it.isLetter() } && trimmed.filter { it.isLetter() }.all { it.isUpperCase() }
        val keyword = if (cleaned.length >= 3) cleaned else trimmed
        val renameSuggestion = when {
            stripped && isAllCaps -> toTitleCase(keyword)
            stripped -> keyword
            isAllCaps -> toTitleCase(trimmed)
            else -> ""
        }
        return MerchantSuggestion(
            mode = if (stripped) MatchMode.Keyword else MatchMode.Exact,
            keyword = keyword,
            renameTo = renameSuggestion,
        )
    }

    /**
     * True if [payee] already has a direct rule (`ruleCount > 0`) or is matched by an existing
     * non-schedule rule on `payee` or `imported_payee` (`is`, `oneOf`, or `contains`).
     */
    fun isCoveredByRules(payee: PayeeStat, rules: List<Rule>): Boolean {
        if (payee.ruleCount > 0) return true
        val id = payee.id.raw
        val name = payee.name
        return rules.any { rule ->
            rule.scheduleId == null && rule.conditions.any { c ->
                when {
                    c.field == "payee" && c.op == "is" -> (c.value as? RuleValue.Text)?.value == id
                    c.field == "payee" && c.op == "oneOf" -> id in (c.value as? RuleValue.Items)?.values.orEmpty()
                    (c.field == "payee" || c.field == "imported_payee") && c.op == "contains" -> {
                        val kw = (c.value as? RuleValue.Text)?.value?.trim().orEmpty()
                        kw.isNotEmpty() && name.contains(kw, ignoreCase = true)
                    }
                    else -> false
                }
            }
        }
    }

    /** True when a merchant name would be swept up by a keyword rule (and not excluded). */
    fun matchesKeyword(payeeName: String, keyword: String, excludeKeywords: List<String> = emptyList()): Boolean {
        val kw = keyword.trim()
        if (kw.isEmpty() || !payeeName.contains(kw, ignoreCase = true)) return false
        return excludeKeywords.none { ex ->
            val t = ex.trim()
            t.isNotEmpty() && payeeName.contains(t, ignoreCase = true)
        }
    }

    /**
     * Picks the clearest distinguishing word from [candidateName] to exclude it from a rule
     * matching [keyword] (e.g. keyword `"SHELL"` + `"SHELLFISH MARKET #42"` -> `"SHELLFISH"`,
     * keyword `"APPLE"` + `"APPLE STORE #123"` -> `"STORE"`).
     */
    fun extractExclusionTerm(keyword: String, candidateName: String): String {
        val kw = keyword.trim()
        val cleaned = suggest(candidateName).keyword.trim().ifEmpty { candidateName.trim() }
        if (cleaned.isEmpty()) return ""
        val kwWords = kw.lowercase().split(Regex("""[\s./*_-]+""")).filter { it.isNotEmpty() }.toSet()
        val words = cleaned.split(Regex("""\s+""")).map { it.trim(',', '.', '-', '_', '*', '#') }.filter { it.length >= 2 }
        // Prefer a longer word that accidentally contains the keyword (e.g. SHELLFISH vs SHELL).
        words.firstOrNull { w -> kw.isNotEmpty() && w.contains(kw, ignoreCase = true) && !w.equals(kw, ignoreCase = true) }?.let { return it }
        // Otherwise pick the first meaningful word not already in the keyword (e.g. STORE in APPLE STORE).
        words.firstOrNull { w -> w.lowercase() !in kwWords }?.let { return it }
        return cleaned
    }

    /**
     * Builds the [RuleDraft] for previewing or saving.
     *
     * In [MatchMode.Keyword] without extra filters, matches `imported_payee contains kw OR
     * payee contains kw` so it catches both bank-synced descriptions and manually entered
     * transactions. When exclusions or a notes filter are present, switches to `AND` so every
     * narrowing condition must hold.
     */
    fun buildDraft(
        mode: MatchMode,
        payeeId: PayeeId,
        keyword: String,
        categoryId: CategoryId?,
        targetPayeeId: PayeeId? = null,
        excludeKeywords: List<String> = emptyList(),
        notesContains: String = "",
    ): RuleDraft {
        val kw = keyword.trim()
        val excludes = excludeKeywords.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
        val notes = notesContains.trim()

        val (conditionsOp, conditions) = when (mode) {
            MatchMode.Exact -> "and" to buildList {
                add(RuleClause("payee", "is", RuleValue.Text(payeeId.raw), "id"))
                if (notes.isNotEmpty()) {
                    add(RuleClause("notes", "contains", RuleValue.Text(notes), "string"))
                }
            }
            MatchMode.Keyword -> if (excludes.isEmpty() && notes.isEmpty()) {
                "or" to listOf(
                    RuleClause("imported_payee", "contains", RuleValue.Text(kw), "string"),
                    RuleClause("payee", "contains", RuleValue.Text(kw), "string"),
                )
            } else {
                "and" to buildList {
                    add(RuleClause("payee", "contains", RuleValue.Text(kw), "string"))
                    excludes.forEach { ex ->
                        add(RuleClause("payee", "doesNotContain", RuleValue.Text(ex), "string"))
                    }
                    if (notes.isNotEmpty()) {
                        add(RuleClause("notes", "contains", RuleValue.Text(notes), "string"))
                    }
                }
            }
        }
        val actions = buildList {
            if (targetPayeeId != null) {
                add(RuleClause("payee", "set", RuleValue.Text(targetPayeeId.raw), "id"))
            }
            if (categoryId != null) {
                add(RuleClause("category", "set", RuleValue.Text(categoryId.raw), "id"))
            }
        }
        return RuleDraft(
            stage = null,
            conditionsOp = conditionsOp,
            conditions = conditions,
            actions = actions,
        )
    }

    private fun toTitleCase(input: String): String = input
        .lowercase()
        .split(' ')
        .joinToString(" ") { word ->
            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
}
