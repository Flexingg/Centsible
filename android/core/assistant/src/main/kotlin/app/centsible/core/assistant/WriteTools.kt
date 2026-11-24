package app.centsible.core.assistant

import app.centsible.core.model.AccountType
import app.centsible.core.model.AmountOp
import app.centsible.core.model.AverageBasis
import app.centsible.core.model.BatchChange
import app.centsible.core.model.BudgetPot
import app.centsible.core.model.CategoryId
import app.centsible.core.model.EndMode
import app.centsible.core.model.Frequency
import app.centsible.core.model.Money
import app.centsible.core.model.NewTransaction
import app.centsible.core.model.PayeeId
import app.centsible.core.model.Recurrence
import app.centsible.core.model.RuleClause
import app.centsible.core.model.RuleDraft
import app.centsible.core.model.RuleValue
import app.centsible.core.model.ScheduleDraft
import app.centsible.core.model.SplitEdit
import app.centsible.core.model.TransactionId
import app.centsible.core.model.TransactionPatch
import app.centsible.core.model.Update
import app.centsible.core.model.YearMonth
import app.centsible.core.domain.recreate
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

// Changes. Each one is shown to the person as a card first (the preview) and runs only when
// they approve it. Where a change can be taken back cleanly, it returns the call that does.

private fun ToolContext.thisMonth() = YearMonth.of(now.year, now.monthValue)
private fun Args.monthOr(ctx: ToolContext) = optMonth("month") ?: ctx.thisMonth()
private fun call(name: String, vararg pairs: Pair<String, Any?>) = ToolCall(name, j(*pairs).toString())
private fun done(summary: String, undo: ToolCall? = null, vararg extra: Pair<String, Any?>) = ToolOutput(j("ok" to true, "done" to summary, *extra), summary, undo)
private fun monthName(m: YearMonth) = java.time.Month.of(m.month).getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault()) + " " + m.year
private fun onOff(b: Boolean) = if (b) "on" else "off"

// ── Transactions ──

internal fun ToolsBuilder.transactionWrites(gw: Gateways) {
    val G = ToolGroup.Transactions

    write(
        G, "create_transaction",
        "Add a transaction. Amount is negative for money out, positive for money in. Give payee_id for a merchant you know, or payee_name for a new one. Splits divide it across categories and must add up to the amount.",
        schema {
            string("account_id", "The account.", required = true)
            string("date", "YYYY-MM-DD.", required = true)
            amount("amount", "The amount.", required = true)
            string("payee_id", "An existing merchant.")
            string("payee_name", "A merchant by name (created if new).")
            string("category_id", "The category (leave out for a split).")
            string("notes", "Notes.")
            boolean("cleared", "Cleared (default false).")
            objects("splits", "Splits: each with amount, category_id and optional notes.") {
                amount("amount", "This split's amount.", required = true)
                string("category_id", "This split's category.")
                string("notes", "Notes.")
            }
        },
        preview = { a, ctx ->
            val splits = a.optObjects("splits").orEmpty()
            Preview(
                "Add a transaction",
                listOfNotNull(
                    "${a.date("date")} · ${a.optPayee("payee_id")?.let { ctx.names.payee(it) } ?: a.optString("payee_name") ?: "No payee"} · ${a.money("amount").text()}",
                    "Account: ${ctx.names.requireAccount(a.account("account_id")).name}",
                    a.optCategory("category_id")?.let { "Category: ${ctx.names.requireCategory(it)}" },
                    a.optString("notes")?.let { "Notes: $it" },
                ) + splits.map { s -> "  Split ${s.money("amount").text()} → ${s.optCategory("category_id")?.let { ctx.names.category(it) } ?: "uncategorized"}" },
            )
        },
    ) { a, ctx ->
        val id = TransactionId(UUID.randomUUID().toString())
        val splits = a.optObjects("splits").orEmpty().map { NewTransaction.Split(it.money("amount"), it.optCategory("category_id"), it.optString("notes")) }
        if (splits.isNotEmpty() && splits.sumOf { it.amount.minor } != a.money("amount").minor) throw ToolInputException("The splits add up to ${Money(splits.sumOf { it.amount.minor }).text()}, not ${a.money("amount").text()}.")
        val t = gw.engine.createTransaction(
            ctx.budget,
            NewTransaction(id, a.account("account_id"), a.date("date"), a.money("amount"), a.optPayee("payee_id"), a.optString("payee_name").takeIf { !a.has("payee_id") }, a.optCategory("category_id").takeIf { splits.isEmpty() }, a.optString("notes"), a.optBool("cleared"), splits),
        )
        done("Added ${t.amount.text()} on ${t.date}", call("delete_transaction", "id" to t.id.raw), "transaction" to ctx.names.encode(t))
    }

    write(
        G, "update_transaction",
        "Change one transaction: any of account, date, amount, payee, category, notes, cleared, or its splits (which replace all splits; an empty list unsplits it). Only the fields you pass change; pass category_id null to clear the category.",
        schema {
            string("id", "The transaction.", required = true)
            string("account_id", "Move to this account.")
            string("date", "YYYY-MM-DD.")
            amount("amount", "New amount.")
            string("payee_id", "An existing merchant.")
            string("payee_name", "A merchant by name (created if new).")
            nullableString("category_id", "New category, or null for none.")
            nullableString("notes", "New notes, or null to clear.")
            boolean("cleared", "Cleared.")
            objects("splits", "Replace the splits (empty list unsplits). Each: amount, category_id, notes, and id for a split that already exists.") {
                string("id", "An existing split's id.")
                amount("amount", "This split's amount.", required = true)
                string("category_id", "This split's category.")
                string("notes", "Notes.")
            }
        },
        preview = { a, ctx ->
            val t = gw.engine.transaction(ctx.budget, a.transaction("id"))
            val lines = mutableListOf("${t.date} · ${ctx.names.payee(t.payeeId) ?: t.payeeName ?: "No payee"} · ${t.amount.text()}")
            a.optAccount("account_id")?.let { lines += "Account: ${ctx.names.account(t.accountId)} → ${ctx.names.requireAccount(it).name}" }
            a.optDate("date")?.let { lines += "Date: ${t.date} → $it" }
            a.optMoney("amount")?.let { lines += "Amount: ${t.amount.text()} → ${it.text()}" }
            if (a.has("payee_id") || a.has("payee_name")) lines += "Payee: ${ctx.names.payee(t.payeeId) ?: t.payeeName ?: "none"} → ${a.optPayee("payee_id")?.let { ctx.names.requirePayee(it) } ?: a.optString("payee_name")}"
            if (a.raw.containsKey("category_id")) lines += "Category: ${ctx.names.category(t.categoryId) ?: "none"} → ${a.optCategory("category_id")?.let { ctx.names.requireCategory(it) } ?: "none"}"
            if (a.raw.containsKey("notes")) lines += "Notes: ${t.notes ?: "none"} → ${a.optString("notes") ?: "none"}"
            a.optBool("cleared")?.let { lines += "Cleared: ${onOff(t.cleared)} → ${onOff(it)}" }
            a.optObjects("splits")?.let { s ->
                val parts = s.map { x -> "${x.money("amount").text()} ${x.optCategory("category_id")?.let { c -> ctx.names.category(c) } ?: "uncategorized"}" }
                lines += if (s.isEmpty()) "Remove the splits" else "Splits: " + parts.joinToString()
            }
            Preview("Edit a transaction", lines)
        },
    ) { a, ctx ->
        val before = gw.engine.transaction(ctx.budget, a.transaction("id"))
        val patch = TransactionPatch(
            accountId = a.optAccount("account_id")?.let { Update.Set(it) } ?: Update.Keep,
            date = a.optDate("date")?.let { Update.Set(it) } ?: Update.Keep,
            amount = a.optMoney("amount")?.let { Update.Set(it) } ?: Update.Keep,
            payeeId = a.optPayee("payee_id")?.let { Update.Set(it) } ?: Update.Keep,
            payeeName = a.optString("payee_name")?.takeIf { !a.has("payee_id") }?.let { Update.Set(it) } ?: Update.Keep,
            categoryId = if (a.raw.containsKey("category_id")) Update.Set(a.optCategory("category_id")) else Update.Keep,
            notes = if (a.raw.containsKey("notes")) Update.Set(a.optString("notes")) else Update.Keep,
            cleared = a.optBool("cleared")?.let { Update.Set(it) } ?: Update.Keep,
            splits = a.optObjects("splits")?.let { s -> Update.Set(s.map { SplitEdit(it.optString("id")?.let(::TransactionId), it.money("amount"), it.optCategory("category_id"), it.optString("notes")) }) } ?: Update.Keep,
        )
        if (patch.isEmpty) throw ToolInputException("Nothing to change: pass at least one field.")
        val after = gw.engine.updateTransaction(ctx.budget, before.id, patch)
        // Undo puts back the fields that changed (splits can't be put back exactly, so not then).
        val undo = if (a.has("splits")) null else call(
            "update_transaction",
            "id" to before.id.raw,
            "account_id" to before.accountId.raw.takeIf { a.has("account_id") },
            "date" to before.date.takeIf { a.has("date") },
            "amount" to before.amount.takeIf { a.has("amount") },
            "payee_id" to before.payeeId?.raw.takeIf { a.has("payee_id") || a.has("payee_name") },
            *(if (a.raw.containsKey("category_id")) arrayOf("category_id" to (before.categoryId?.raw ?: ExplicitNull)) else emptyArray()),
            *(if (a.raw.containsKey("notes")) arrayOf("notes" to (before.notes ?: ExplicitNull)) else emptyArray()),
            "cleared" to before.cleared.takeIf { a.has("cleared") },
        )
        done("Updated the ${before.date} transaction", undo, "transaction" to ctx.names.encode(after))
    }

    write(
        G, "delete_transaction",
        "Delete a transaction.",
        schema { string("id", "The transaction.", required = true) },
        preview = { a, ctx ->
            val t = gw.engine.transaction(ctx.budget, a.transaction("id"))
            Preview("Delete a transaction", listOfNotNull("${t.date} · ${ctx.names.payee(t.payeeId) ?: t.payeeName ?: "No payee"} · ${t.amount.text()}", ctx.names.category(t.categoryId)?.let { "Category: $it" }, "Account: ${ctx.names.account(t.accountId)}"))
        },
    ) { a, ctx ->
        val t = gw.engine.transaction(ctx.budget, a.transaction("id"))
        gw.engine.deleteTransaction(ctx.budget, t.id)
        val back = t.recreate()
        val undo = call(
            "create_transaction",
            "account_id" to back.accountId.raw, "date" to back.date, "amount" to back.amount, "payee_id" to back.payeeId?.raw, "payee_name" to back.payeeName,
            "category_id" to back.categoryId?.raw, "notes" to back.notes, "cleared" to back.cleared,
            "splits" to back.splits.takeIf { it.isNotEmpty() }?.map { j("amount" to it.amount, "category_id" to it.categoryId?.raw, "notes" to it.notes) },
        )
        done("Deleted the ${t.amount.text()} transaction from ${t.date}", undo)
    }

    write(
        G, "set_transaction_categories",
        "Set the category of many transactions at once, each its own (or null for none). Use it to categorize the review inbox or recategorize a batch. Split transactions are skipped (edit their splits with update_transaction).",
        schema {
            objects("changes", "One per transaction.", required = true) {
                string("transaction_id", "The transaction.", required = true)
                nullableString("category_id", "Its new category, or null for none.", required = true)
            }
        },
        preview = { a, ctx ->
            val changes = a.objects("changes")
            if (changes.isEmpty()) throw ToolInputException("Pass at least one change.")
            val lines = changes.take(12).map { c ->
                val t = gw.engine.transaction(ctx.budget, c.transaction("transaction_id"))
                "${t.date} ${ctx.names.payee(t.payeeId) ?: t.payeeName ?: ""} ${t.amount.text()}: ${ctx.names.category(t.categoryId) ?: "none"} → ${c.optCategory("category_id")?.let { ctx.names.requireCategory(it) } ?: "none"}"
            }
            Preview("Set categories on ${changes.size} transaction${if (changes.size == 1) "" else "s"}", lines + listOfNotNull(if (changes.size > 12) "…and ${changes.size - 12} more" else null))
        },
    ) { a, ctx ->
        val changes = a.objects("changes").map { it.transaction("transaction_id") to it.optCategory("category_id") }
        val before = changes.associate { (id, _) -> id to runCatching { gw.engine.transaction(ctx.budget, id).categoryId }.getOrNull() }
        var updated = 0
        val skipped = mutableListOf<String>()
        changes.groupBy({ it.second }, { it.first }).forEach { (cat, ids) ->
            val r = gw.transactions.batch(ctx.budget, ids, BatchChange.Category(cat))
            updated += r.updated
            skipped += r.skipped.map { "${it.id.raw}: ${it.reason}" }
        }
        val undo = call("set_transaction_categories", "changes" to before.map { (id, c) -> j("transaction_id" to id.raw, "category_id" to (c?.raw ?: ExplicitNull)) })
        done("Categorized $updated transaction${if (updated == 1) "" else "s"}", undo, "skipped" to skipped.takeIf { it.isNotEmpty() })
    }

    write(
        G, "mark_reviewed",
        "Mark transactions as reviewed (out of this person's inbox), or all of them. Only takes effect in the \"new\" inbox: the uncategorized and uncleared ones empty as transactions get a category or are cleared.",
        schema {
            strings("transaction_ids", "These transactions.")
            boolean("all", "Everything in the inbox.")
        },
        preview = { a, _ -> Preview("Mark as reviewed", listOf(if (a.bool("all", false)) "Everything in your review inbox" else "${a.strings("transaction_ids").size} transactions")) },
    ) { a, ctx ->
        val n = if (a.bool("all", false)) gw.transactions.reviewAll(ctx.budget) else gw.transactions.markReviewed(ctx.budget, a.strings("transaction_ids").map(::TransactionId))
        done("Marked as reviewed; $n left in the inbox")
    }

    write(
        G, "link_transfer",
        "Make two transactions one transfer between accounts (both stop counting as spending or income).",
        schema {
            string("from_transaction_id", "Money leaving one account.", required = true)
            string("to_transaction_id", "The same money arriving in the other.", required = true)
        },
        preview = { a, ctx ->
            val f = gw.engine.transaction(ctx.budget, a.transaction("from_transaction_id"))
            val t = gw.engine.transaction(ctx.budget, a.transaction("to_transaction_id"))
            Preview("Link as a transfer", listOf("${f.date} ${ctx.names.account(f.accountId)} ${f.amount.text()}", "${t.date} ${ctx.names.account(t.accountId)} ${t.amount.text()}"))
        },
    ) { a, ctx ->
        gw.transactions.linkTransfer(ctx.budget, a.transaction("from_transaction_id"), a.transaction("to_transaction_id"))
        done("Linked them as a transfer")
    }

    write(
        G, "dismiss_transfer_match",
        "Say two transactions are not a transfer, so they stop being suggested.",
        schema {
            string("from_transaction_id", "One side.", required = true)
            string("to_transaction_id", "The other.", required = true)
        },
        preview = { _, _ -> Preview("Not a transfer", listOf("Stop suggesting these two as a transfer")) },
    ) { a, ctx ->
        gw.transactions.dismissTransfer(ctx.budget, a.transaction("from_transaction_id"), a.transaction("to_transaction_id"))
        done("Dismissed the suggestion")
    }

    write(
        G, "merge_duplicates",
        "Merge two copies of the same transaction (same account and amount) into one; the bank's copy is kept, category and notes carry over.",
        schema {
            string("a", "One copy.", required = true)
            string("b", "The other.", required = true)
        },
        preview = { a, ctx ->
            val x = gw.engine.transaction(ctx.budget, a.transaction("a"))
            Preview("Merge duplicates", listOf("${x.date} ${ctx.names.payee(x.payeeId) ?: x.payeeName ?: ""} ${x.amount.text()} (two copies → one)"))
        },
    ) { a, ctx ->
        val kept = gw.transactions.merge(ctx.budget, a.transaction("a"), a.transaction("b"))
        done("Merged into one", null, "kept" to ctx.names.encode(kept))
    }

    write(
        G, "dismiss_duplicate",
        "Say two similar transactions are both real, so they stop being flagged.",
        schema {
            string("a", "One.", required = true)
            string("b", "The other.", required = true)
        },
        preview = { _, _ -> Preview("Not duplicates", listOf("Keep both and stop flagging them")) },
    ) { a, ctx ->
        gw.transactions.dismissDuplicate(ctx.budget, a.transaction("a"), a.transaction("b"))
        done("Kept both")
    }
}

// ── Budget amounts ──

/** Each category's budgeted amount in a month, to undo a change that touches many. */
private suspend fun Gateways.budgetedIn(ctx: ToolContext, month: YearMonth): Map<CategoryId, Money> =
    engine.budgetMonth(ctx.budget, month).groups.flatMap { it.categories }.associate { it.id to it.budgeted }

private fun undoBudgets(month: YearMonth, before: Map<CategoryId, Money>, after: Map<CategoryId, Money>): ToolCall? {
    val changed = before.filter { (id, v) -> after[id] != null && after[id] != v }
    if (changed.isEmpty()) return null
    return call("set_budget_amounts", "month" to month.raw, "amounts" to changed.map { (id, v) -> j("category_id" to id.raw, "amount" to v) })
}

internal fun ToolsBuilder.budgetWrites(gw: Gateways) {
    val G = ToolGroup.Budget

    write(
        G, "set_budget_amounts",
        "Set what's budgeted for one or more categories in a month (envelope budgets). Amounts replace what's there.",
        schema {
            month()
            objects("amounts", "One per category.", required = true) {
                string("category_id", "The category.", required = true)
                amount("amount", "What to budget.", required = true)
            }
        },
        preview = { a, ctx ->
            val m = a.monthOr(ctx)
            val now = gw.budgetedIn(ctx, m)
            Preview(
                "Budget ${monthName(m)}",
                a.objects("amounts").map { x -> "${ctx.names.requireCategory(x.category("category_id"))}: ${(now[x.category("category_id")] ?: Money.Zero).text()} → ${x.money("amount").text()}" },
            )
        },
    ) { a, ctx ->
        val m = a.monthOr(ctx)
        val before = gw.budgetedIn(ctx, m)
        var month = gw.engine.budgetMonth(ctx.budget, m)
        a.objects("amounts").forEach { x -> month = gw.engine.setBudgeted(ctx.budget, m, x.category("category_id"), x.money("amount")) }
        val after = month.groups.flatMap { it.categories }.associate { it.id to it.budgeted }
        done("Budgeted ${a.objects("amounts").size} categor${if (a.objects("amounts").size == 1) "y" else "ies"} for ${monthName(m)}", undoBudgets(m, before, after), "to_budget_now" to month.toBudget)
    }

    write(
        G, "move_money",
        "Move budgeted money between two categories, or to/from To Budget (use \"to_budget\" as the id).",
        schema {
            month()
            string("from", "A category id, or \"to_budget\".", required = true)
            string("to", "A category id, or \"to_budget\".", required = true)
            amount("amount", "How much (positive).", required = true)
        },
        preview = { a, ctx ->
            suspend fun label(x: String) = if (x == "to_budget") "To Budget" else ctx.names.requireCategory(CategoryId(x))
            Preview("Move money · ${monthName(a.monthOr(ctx))}", listOf("${a.money("amount").text()} from ${label(a.string("from"))} to ${label(a.string("to"))}"))
        },
    ) { a, ctx ->
        fun pot(x: String) = if (x == "to_budget") BudgetPot.ToBudget else BudgetPot.Envelope(CategoryId(x))
        val amount = a.money("amount")
        if (amount.minor <= 0) throw ToolInputException("The amount should be positive.")
        val m = a.monthOr(ctx)
        val month = gw.engine.moveMoney(ctx.budget, m, pot(a.string("from")), pot(a.string("to")), amount, UUID.randomUUID().toString())
        done("Moved ${amount.text()}", call("move_money", "month" to m.raw, "from" to a.string("to"), "to" to a.string("from"), "amount" to amount), "to_budget_now" to month.toBudget)
    }

    write(
        G, "hold_for_next_month",
        "Hold part of To Budget for next month (or release the hold with amount null).",
        schema {
            month()
            number("amount", "How much to hold, or null to release.")
        },
        preview = { a, ctx -> Preview("Hold for next month · ${monthName(a.monthOr(ctx))}", listOf(a.optMoney("amount")?.let { "Hold ${it.text()}" } ?: "Release what's held")) },
    ) { a, ctx ->
        val m = a.monthOr(ctx)
        val before = gw.engine.budgetMonth(ctx.budget, m).forNextMonth
        gw.engine.holdForNextMonth(ctx.budget, m, a.optMoney("amount"))
        done(a.optMoney("amount")?.let { "Holding ${it.text()} for next month" } ?: "Released the hold", call("hold_for_next_month", "month" to m.raw, "amount" to before.takeIf { !it.isZero }))
    }

    write(
        G, "set_rollover",
        "Turn rollover on or off for a category: with it on, overspending carries into next month's balance instead of coming out of To Budget.",
        schema {
            month()
            string("category_id", "The category.", required = true)
            boolean("on", "Rollover on or off.", required = true)
        },
        preview = { a, ctx -> Preview("Rollover", listOf("${ctx.names.requireCategory(a.category("category_id"))}: turn ${onOff(a.bool("on", true))}")) },
    ) { a, ctx ->
        val m = a.monthOr(ctx)
        gw.engine.setCarryover(ctx.budget, m, a.category("category_id"), a.bool("on", true))
        done("Rollover ${onOff(a.bool("on", true))}", call("set_rollover", "month" to m.raw, "category_id" to a.string("category_id"), "on" to !a.bool("on", true)))
    }

    write(
        G, "apply_budget_suggestions",
        "Budget categories at their suggested amounts (the 3, 6 or 12-month average spending, rounded up), all categories whose suggestion differs or just the ones given. See get_budget_suggestions first.",
        schema {
            month()
            enum("basis", listOf("3", "6", "12"), "Which average.", required = true)
            strings("category_ids", "Only these (default: every category whose suggestion differs).")
        },
        preview = { a, ctx ->
            val basis = AverageBasis.entries.first { it.months.toString() == a.string("basis") }
            val p = gw.planAhead.autopilot(ctx.budget, a.monthOr(ctx))
            val only = a.optStrings("category_ids")?.toSet()
            val chosen = p.suggestions.filter { (only == null && it.differs(basis)) || (only != null && it.categoryId.raw in only) }
            Preview("Budget the ${basis.label}s · ${monthName(a.monthOr(ctx))}", chosen.take(15).map { "${it.name}: ${it.budgeted.text()} → ${it.suggestion(basis).text()}" } + listOfNotNull(if (chosen.size > 15) "…and ${chosen.size - 15} more" else null))
        },
    ) { a, ctx ->
        val m = a.monthOr(ctx)
        val basis = AverageBasis.entries.first { it.months.toString() == a.string("basis") }
        val p = gw.planAhead.autopilot(ctx.budget, m)
        val only = a.optStrings("category_ids")?.toSet()
        val ids = p.suggestions.filter { (only == null && it.differs(basis)) || (only != null && it.categoryId.raw in only) }.map { it.categoryId }
        val before = gw.budgetedIn(ctx, m)
        val month = gw.planAhead.applyAutopilot(ctx.budget, m, basis, ids)
        done("Budgeted ${ids.size} categories from the ${basis.label}", undoBudgets(m, before, month.groups.flatMap { it.categories }.associate { it.id to it.budgeted }), "to_budget_now" to month.toBudget)
    }

    write(
        G, "cover_overspending",
        "Cover every overspent category in a month: from To Budget first, then from the categories with the most left.",
        schema { month() },
        preview = { a, ctx ->
            val p = gw.planAhead.autopilot(ctx.budget, a.monthOr(ctx))
            Preview("Cover overspending · ${monthName(a.monthOr(ctx))}", p.coverMoves.map { "${it.amount.text()}: ${it.fromName} → ${it.toName}" }.ifEmpty { listOf("Nothing is overspent") } + listOfNotNull(if (!p.uncovered.isZero) "${p.uncovered.text()} can't be covered" else null))
        },
    ) { a, ctx ->
        val m = a.monthOr(ctx)
        val before = gw.budgetedIn(ctx, m)
        val r = gw.planAhead.coverOverspending(ctx.budget, m)
        done("Covered with ${r.moves.size} moves", undoBudgets(m, before, r.month.groups.flatMap { it.categories }.associate { it.id to it.budgeted }), "left_uncovered" to r.uncovered)
    }

    write(
        G, "run_budget_automations",
        "Fill a month's budget from the categories' budget automations (and #template notes). overwrite replaces amounts already budgeted; otherwise only empty categories are filled.",
        schema {
            month()
            boolean("overwrite", "Replace amounts already budgeted (default false).")
            strings("category_ids", "Only these categories.")
        },
        preview = { a, ctx -> Preview("Run budget automations · ${monthName(a.monthOr(ctx))}", listOf(if (a.bool("overwrite", false)) "Replace what's budgeted" else "Fill empty categories only", a.optStrings("category_ids")?.let { "${it.size} categories" } ?: "Every category with automations")) },
    ) { a, ctx ->
        val m = a.monthOr(ctx)
        val before = gw.budgetedIn(ctx, m)
        val r = gw.automations.apply(ctx.budget, m, a.bool("overwrite", false), a.optStrings("category_ids")?.map(::CategoryId))
        done(r.message, undoBudgets(m, before, gw.budgetedIn(ctx, m)), "details" to r.details)
    }
}

// ── Categories ──

internal fun ToolsBuilder.categoryWrites(gw: Gateways) {
    val G = ToolGroup.Categories

    write(
        G, "create_category",
        "Create a category: in a group (level 2) with group_id, or as a sub-category of another with parent_id (down to level 5).",
        schema {
            string("name", "Its name.", required = true)
            string("group_id", "Make it a top-level category of this group.")
            string("parent_id", "Make it a sub-category of this category.")
        },
        preview = { a, ctx -> Preview("New category", listOf("${a.string("name")} in ${a.optCategory("parent_id")?.let { ctx.names.requireCategory(it) } ?: ctx.names.requireGroup(a.group("group_id")).name}")) },
    ) { a, ctx ->
        val c = a.optCategory("parent_id")?.let { gw.engine.createSubcategory(ctx.budget, a.string("name"), it) }
            ?: gw.engine.createCategory(ctx.budget, a.string("name"), a.group("group_id"))
        done("Created ${c.name}", call("delete_category", "category_id" to c.id.raw), "category_id" to c.id.raw)
    }

    write(
        G, "update_category",
        "Rename, hide or show, or move a category (with its sub-categories): parent_id puts it under another category, null brings it to the top of its group; group_id moves it to the top of another group.",
        schema {
            string("category_id", "The category.", required = true)
            string("name", "New name.")
            boolean("hidden", "Hide or show it.")
            nullableString("parent_id", "Put it under this category, or null for the top of its group.")
            string("group_id", "Move it to the top of this group.")
        },
        preview = { a, ctx ->
            val id = a.category("category_id")
            val name = ctx.names.requireCategory(id)
            Preview(
                "Change a category",
                listOfNotNull(
                    a.optString("name")?.let { "$name → $it" } ?: name,
                    a.optBool("hidden")?.let { if (it) "Hide it" else "Show it" },
                    if (a.raw.containsKey("parent_id")) "Move under ${a.optCategory("parent_id")?.let { ctx.names.requireCategory(it) } ?: "the top of its group"}" else null,
                    a.optString("group_id")?.let { "Move to ${ctx.names.requireGroup(a.group("group_id")).name}" },
                ),
            )
        },
    ) { a, ctx ->
        val id = a.category("category_id")
        val snap = ctx.names.get()
        val oldName = snap.tree[id]?.name
        val oldHidden = snap.tree[id]?.hidden
        val oldParent = snap.tree.parentOf(id)
        val oldGroup = snap.tree[id]?.groupId
        if (a.has("name") || a.has("hidden") || a.has("group_id")) gw.engine.updateCategory(ctx.budget, id, a.optString("name"), a.optBool("hidden"), a.optString("group_id")?.let { app.centsible.core.model.CategoryGroupId(it) })
        if (a.raw.containsKey("parent_id")) gw.engine.setCategoryParent(ctx.budget, id, a.optCategory("parent_id"))
        val undo = call(
            "update_category",
            "category_id" to id.raw,
            "name" to oldName.takeIf { a.has("name") },
            "hidden" to oldHidden.takeIf { a.has("hidden") },
            *(if (a.raw.containsKey("parent_id") || a.has("group_id")) arrayOf("parent_id" to (oldParent?.raw ?: ExplicitNull)) else emptyArray()),
            "group_id" to oldGroup?.raw.takeIf { a.has("group_id") && oldParent == null },
        )
        done("Updated the category", undo)
    }

    write(
        G, "delete_category",
        "Delete a category. Its transactions and budget move to move_to_category_id (required if it has any); its sub-categories move up a level.",
        schema {
            string("category_id", "The category.", required = true)
            string("move_to_category_id", "Where its transactions and budget go.")
        },
        preview = { a, ctx -> Preview("Delete a category", listOfNotNull(ctx.names.requireCategory(a.category("category_id")), a.optCategory("move_to_category_id")?.let { "Its transactions and budget move to ${ctx.names.requireCategory(it)}" })) },
    ) { a, ctx ->
        gw.engine.deleteCategory(ctx.budget, a.category("category_id"), a.optCategory("move_to_category_id"))
        done("Deleted the category")
    }

    write(
        G, "create_category_group",
        "Create a category group (a top level, like Needs or Wants).",
        schema { string("name", "Its name.", required = true) },
        preview = { a, _ -> Preview("New group", listOf(a.string("name"))) },
    ) { a, ctx ->
        val g = gw.engine.createCategoryGroup(ctx.budget, a.string("name"))
        done("Created ${g.name}", call("delete_category_group", "group_id" to g.id.raw), "group_id" to g.id.raw)
    }

    write(
        G, "update_category_group",
        "Rename a group, or hide or show it.",
        schema {
            string("group_id", "The group.", required = true)
            string("name", "New name.")
            boolean("hidden", "Hide or show it.")
        },
        preview = { a, ctx ->
            val g = ctx.names.requireGroup(a.group("group_id"))
            Preview("Change a group", listOfNotNull(a.optString("name")?.let { "${g.name} → $it" } ?: g.name, a.optBool("hidden")?.let { if (it) "Hide it" else "Show it" }))
        },
    ) { a, ctx ->
        val g = ctx.names.requireGroup(a.group("group_id"))
        gw.engine.updateCategoryGroup(ctx.budget, g.id, a.optString("name"), a.optBool("hidden"))
        done("Updated the group", call("update_category_group", "group_id" to g.id.raw, "name" to g.name.takeIf { a.has("name") }, "hidden" to g.hidden.takeIf { a.has("hidden") }))
    }

    write(
        G, "delete_category_group",
        "Delete a group and its categories; their transactions and budgets move to move_to_category_id.",
        schema {
            string("group_id", "The group.", required = true)
            string("move_to_category_id", "Where its transactions and budgets go.")
        },
        preview = { a, ctx -> Preview("Delete a group", listOfNotNull(ctx.names.requireGroup(a.group("group_id")).name + " and its categories", a.optCategory("move_to_category_id")?.let { "Transactions and budgets move to ${ctx.names.requireCategory(it)}" })) },
    ) { a, ctx ->
        gw.engine.deleteCategoryGroup(ctx.budget, a.group("group_id"), a.optCategory("move_to_category_id"))
        done("Deleted the group")
    }

    write(
        G, "set_off_books",
        "Mark a category as a transfer kept off the books (left out of totals, reports and reviews: for card payments and unmatched transfers), or put it back.",
        schema {
            string("category_id", "The category.", required = true)
            boolean("off_books", "Off the books or back on.", required = true)
        },
        preview = { a, ctx -> Preview("Transfer (off the books)", listOf("${ctx.names.requireCategory(a.category("category_id"))}: ${if (a.bool("off_books", true)) "keep off the books" else "back in the budget"}")) },
    ) { a, ctx ->
        gw.planAhead.setOffBooks(ctx.budget, a.category("category_id"), a.bool("off_books", true))
        done("Done", call("set_off_books", "category_id" to a.string("category_id"), "off_books" to !a.bool("off_books", true)))
    }

    write(
        G, "set_category_note",
        "Replace a category's notes (null clears them). Careful: notes may hold #template and #goal lines; keep them unless asked.",
        schema {
            string("category_id", "The category.", required = true)
            nullableString("note", "The new notes, or null.", required = true)
        },
        preview = { a, ctx ->
            val old = gw.planning.categoryNote(ctx.budget, a.category("category_id"))
            Preview("Category notes", listOf(ctx.names.requireCategory(a.category("category_id")), "Was: ${old ?: "(none)"}", "Now: ${a.optString("note") ?: "(none)"}"))
        },
    ) { a, ctx ->
        val old = gw.planning.categoryNote(ctx.budget, a.category("category_id"))
        gw.planning.setCategoryNote(ctx.budget, a.category("category_id"), a.optString("note"))
        done("Saved the notes", call("set_category_note", "category_id" to a.string("category_id"), "note" to (old ?: ExplicitNull)))
    }
}

// ── Rules, merchants and tags ──

internal fun ruleSchema(): JsonObject = schema {
    string("stage", "When it runs: \"pre\", null (default) or \"post\".")
    enum("conditions_op", listOf("and", "or"), "All conditions must match (and) or any (or). Default and.")
    objects(
        "conditions",
        "What a transaction must match. field: payee, imported_payee, notes, amount, date, account, category, category_group, cleared, reconciled, transfer, parent. op: is, isNot, oneOf, notOneOf, contains, doesNotContain, matches, isapprox, isbetween, gt, gte, lt, lte, onBudget, offBudget, hasTags. Ids (payee, account, category) as strings; amounts in cents as integers; isbetween takes {\"num1\": a, \"num2\": b}.",
        required = true,
    ) {
        string("field", "The field.", required = true)
        string("op", "The operator.", required = true)
        any("value", "The value (string, number, boolean, list, or object for isbetween).", required = true)
    }
    objects(
        "actions",
        "What to do: op \"set\" with field category, payee, notes, amount, date, account or cleared and a value (ids as strings, amounts in cents); or op \"delete-transaction\".",
        required = true,
    ) {
        string("op", "\"set\" or \"delete-transaction\".", required = true)
        string("field", "With set: the field.")
        any("value", "With set: the value.")
    }
    integer("limit", "preview_rule only: how many example matches (default 20).", min = 1, max = 50)
}

private fun ruleValue(el: kotlinx.serialization.json.JsonElement?): RuleValue = when (el) {
    null, JsonNull -> RuleValue.Null
    is JsonArray -> RuleValue.Items(el.map { (it as? JsonPrimitive)?.contentOrNull ?: throw ToolInputException("Rule list values should be strings") })
    is JsonObject -> RuleValue.Raw(el.toString())
    is JsonPrimitive -> when {
        el.isString -> RuleValue.Text(el.content)
        el.booleanOrNull != null -> RuleValue.Bool(el.booleanOrNull!!)
        el.longOrNull != null -> RuleValue.Number(el.longOrNull!!)
        el.doubleOrNull != null -> RuleValue.Number(Math.round(el.doubleOrNull!!))
        else -> RuleValue.Text(el.content)
    }
}

private val ID_FIELDS = setOf("payee", "account", "category", "category_group")
private val TEXT_OPS = setOf("contains", "doesNotContain", "matches")

private fun ruleType(field: String?, op: String): String? = when (field) {
    in ID_FIELDS -> if (op in TEXT_OPS) "string" else "id"
    "imported_payee", "notes" -> "string"
    "amount" -> "number"
    "date" -> "date"
    "cleared", "reconciled", "transfer", "parent" -> "boolean"
    else -> null
}

internal fun parseRule(a: Args): RuleDraft {
    val conditions = a.objects("conditions").map { c ->
        val field = c.string("field")
        val op = c.string("op")
        RuleClause(field, op, ruleValue(c.raw["value"]), c.optString("type") ?: ruleType(field, op))
    }
    val actions = a.objects("actions").map { x ->
        val op = x.string("op")
        if (op == "delete-transaction") RuleClause(null, op, RuleValue.Null, null)
        else RuleClause(x.string("field"), op, ruleValue(x.raw["value"]), x.optString("type") ?: ruleType(x.string("field"), "is"))
    }
    if (actions.isEmpty()) throw ToolInputException("A rule needs at least one action.")
    return RuleDraft(a.optString("stage"), a.optString("conditions_op") ?: "and", conditions, actions)
}

private fun RuleDraft.describe(): List<String> =
    listOf("If " + conditions.joinToString(if (conditionsOp == "or") " or " else " and ") { "${it.field} ${it.op} ${it.value.encode()}" }) +
        actions.map { if (it.op == "delete-transaction") "Then delete the transaction" else "Then set ${it.field} to ${it.value.encode()}" }

private fun app.centsible.core.model.Rule.asInput(): Array<Pair<String, Any?>> =
    arrayOf("stage" to stage, "conditions_op" to conditionsOp, "conditions" to conditions.map { it.encode() }, "actions" to actions.map { it.encode() })

internal fun ToolsBuilder.ruleWrites(gw: Gateways) {
    val G = ToolGroup.Rules

    write(
        G, "create_rule", "Create a rule (try preview_rule first to see what it matches).", ruleSchema(),
        preview = { a, _ -> Preview("New rule", parseRule(a).describe()) },
    ) { a, ctx ->
        val r = gw.planning.createRule(ctx.budget, parseRule(a))
        done("Created the rule", call("delete_rule", "id" to r.id), "rule" to r.encode())
    }

    write(
        G, "update_rule", "Replace a rule's conditions and actions.",
        JsonObject(ruleSchema().let { s -> s + ("properties" to JsonObject((s["properties"] as JsonObject) + ("id" to j("type" to "string", "description" to "The rule.")))) }),
        preview = { a, _ -> Preview("Change a rule", parseRule(a).describe()) },
    ) { a, ctx ->
        val id = a.string("id")
        val before = gw.planning.rules(ctx.budget).firstOrNull { it.id == id } ?: throw ToolInputException("No rule $id")
        gw.planning.updateRule(ctx.budget, id, parseRule(a))
        done("Updated the rule", call("update_rule", "id" to id, *before.asInput()))
    }

    write(
        G, "delete_rule", "Delete a rule the person no longer wants (it stops applying to new transactions).",
        schema { string("id", "The rule.", required = true) },
        preview = { a, ctx ->
            val r = gw.planning.rules(ctx.budget).firstOrNull { it.id == a.string("id") } ?: throw ToolInputException("No rule ${a.string("id")}")
            Preview("Delete a rule", RuleDraft(r.stage, r.conditionsOp, r.conditions, r.actions).describe())
        },
    ) { a, ctx ->
        val r = gw.planning.rules(ctx.budget).firstOrNull { it.id == a.string("id") } ?: throw ToolInputException("No rule ${a.string("id")}")
        gw.planning.deleteRule(ctx.budget, r.id)
        done("Deleted the rule", call("create_rule", *r.asInput()))
    }

    write(
        G, "run_rules",
        "Run rules over existing transactions: one rule (optionally only on some transactions), or every rule over transactions since a date.",
        schema {
            string("rule_id", "Run just this rule.")
            strings("transaction_ids", "With rule_id: only these transactions.")
            string("since", "Without rule_id: every rule over transactions since this date, YYYY-MM-DD.")
        },
        preview = { a, _ -> Preview("Run rules", listOf(a.optString("rule_id")?.let { "One rule" + (a.optStrings("transaction_ids")?.let { t -> " on ${t.size} transactions" } ?: " on every transaction it matches") } ?: "Every rule, on transactions since ${a.optDate("since") ?: "the start"}")) },
    ) { a, ctx ->
        val ruleId = a.optString("rule_id")
        if (ruleId != null) {
            val n = gw.rules.run(ctx.budget, ruleId, a.optStrings("transaction_ids")?.map(::TransactionId))
            done("Changed $n transactions")
        } else {
            val (rules, changed) = gw.rules.runAll(ctx.budget, a.optDate("since"))
            done("Ran $rules rules; changed $changed transactions")
        }
    }

    write(
        G, "rename_merchant", "Rename a merchant (payee), for example to tidy a bank's long description.",
        schema {
            string("payee_id", "The merchant.", required = true)
            string("name", "Its new name.", required = true)
        },
        preview = { a, ctx -> Preview("Rename a merchant", listOf("${ctx.names.requirePayee(a.payee("payee_id"))} → ${a.string("name")}")) },
    ) { a, ctx ->
        val old = ctx.names.requirePayee(a.payee("payee_id"))
        gw.planning.renamePayee(ctx.budget, a.payee("payee_id"), a.string("name"))
        done("Renamed", call("rename_merchant", "payee_id" to a.string("payee_id"), "name" to old))
    }

    write(
        G, "merge_merchants", "Merge merchants into one; their transactions and rules move to `into`.",
        schema {
            string("into", "The merchant to keep.", required = true)
            strings("merge", "The merchants to fold into it.", required = true)
        },
        preview = { a, ctx -> Preview("Merge merchants", listOf("Keep ${ctx.names.requirePayee(a.payee("into"))}") + a.strings("merge").map { "Fold in ${ctx.names.requirePayee(PayeeId(it))}" }) },
    ) { a, ctx ->
        gw.planning.mergePayees(ctx.budget, a.payee("into"), a.strings("merge").map(::PayeeId))
        done("Merged ${a.strings("merge").size} into one")
    }

    write(
        G, "delete_merchant", "Delete a merchant nobody uses (any transactions keep no payee).",
        schema { string("payee_id", "The merchant.", required = true) },
        preview = { a, ctx -> Preview("Delete a merchant", listOf(ctx.names.requirePayee(a.payee("payee_id")))) },
    ) { a, ctx ->
        gw.planning.deletePayee(ctx.budget, a.payee("payee_id"))
        done("Deleted the merchant")
    }

    write(
        G, "save_tag", "Create a tag, or change one (pass id): a #word for notes that cuts across categories, like #vacation.",
        schema {
            string("id", "An existing tag to change.")
            string("tag", "The tag word, without #.")
            string("color", "A color like #1baf7a.")
        },
        preview = { a, _ -> Preview(if (a.has("id")) "Change a tag" else "New tag", listOfNotNull(a.optString("tag")?.let { "#$it" }, a.optString("color")?.let { "Color $it" })) },
    ) { a, ctx ->
        val id = a.optString("id")
        if (id == null) {
            val t = gw.planning.createTag(ctx.budget, a.string("tag"), a.optString("color"))
            done("Created #${t.tag}", call("delete_tag", "id" to t.id))
        } else {
            val old = gw.planning.tags(ctx.budget).firstOrNull { it.id == id } ?: throw ToolInputException("No tag $id")
            gw.planning.updateTag(ctx.budget, id, a.optString("tag"), a.optString("color"))
            done("Updated #${a.optString("tag") ?: old.tag}", call("save_tag", "id" to id, "tag" to old.tag, "color" to old.color))
        }
    }

    write(
        G, "delete_tag", "Delete a tag (the #words stay in notes but lose their color and description).",
        schema { string("id", "The tag.", required = true) },
        preview = { a, ctx -> Preview("Delete a tag", listOf("#" + (gw.planning.tags(ctx.budget).firstOrNull { it.id == a.string("id") }?.tag ?: a.string("id")))) },
    ) { a, ctx ->
        val old = gw.planning.tags(ctx.budget).firstOrNull { it.id == a.string("id") }
        gw.planning.deleteTag(ctx.budget, a.string("id"))
        done("Deleted the tag", old?.let { call("save_tag", "tag" to it.tag, "color" to it.color) })
    }
}

// ── Recurring ──

private fun SchemaBuilder.scheduleFields() {
    string("name", "A name, like \"Rent\".")
    string("payee_id", "An existing merchant.")
    string("payee_name", "A merchant by name.")
    string("account_id", "The account it comes from or goes to.")
    amount("amount", "The amount: negative for a bill, positive for income.", required = true)
    enum("amount_rule", listOf("is", "isapprox", "isbetween"), "Exactly, about (default), or between amount and amount_max.")
    amount("amount_max", "With isbetween: the other end.")
    obj("repeats", "How it repeats (leave out for a one-off on `date`).") {
        enum("frequency", Frequency.entries.map { it.name.lowercase() }, "How often.", required = true)
        integer("interval", "Every N of them (default 1).", min = 1)
        string("start", "First date, YYYY-MM-DD.", required = true)
        integer("end_after", "Stop after this many.")
        string("end_on", "Stop on this date.")
        boolean("skip_weekend", "Move off weekends.")
        boolean("weekend_moves_earlier", "With skip_weekend: to the Friday before (default the Monday after).")
    }
    string("date", "A one-off's date, YYYY-MM-DD.")
    boolean("posts_automatically", "Add the transaction automatically when due.")
}

private fun parseSchedule(a: Args): ScheduleDraft {
    val r = a.obj("repeats")?.let { x ->
        Recurrence(
            frequency = x.enum("frequency", Frequency.entries.toTypedArray()),
            interval = x.optInt("interval") ?: 1,
            start = x.date("start"),
            endMode = when {
                x.has("end_after") -> EndMode.AfterOccurrences
                x.has("end_on") -> EndMode.OnDate
                else -> EndMode.Never
            },
            endOccurrences = x.optInt("end_after"),
            endDate = x.optDate("end_on"),
            skipWeekend = x.bool("skip_weekend", false),
            weekendBefore = x.bool("weekend_moves_earlier", false),
        )
    }
    if (r == null && !a.has("date")) throw ToolInputException("Give `repeats` or a one-off `date`.")
    return ScheduleDraft(
        name = a.optString("name"),
        payeeId = a.optPayee("payee_id"),
        payeeName = a.optString("payee_name").takeIf { !a.has("payee_id") },
        accountId = a.optAccount("account_id"),
        amount = a.money("amount"),
        amountOp = a.optEnum("amount_rule", AmountOp.entries.toTypedArray()) ?: AmountOp.IsApprox,
        amountMax = a.optMoney("amount_max"),
        recurrence = r,
        date = a.optDate("date").takeIf { r == null },
        postsTransaction = a.bool("posts_automatically", false),
    )
}

private suspend fun Names.describe(d: ScheduleDraft): List<String> = listOfNotNull(
    "${d.name ?: d.payeeId?.let { payee(it) } ?: d.payeeName ?: "Scheduled"} · ${d.amount.text()}${if (d.amountOp == AmountOp.IsApprox) " (about)" else ""}",
    d.recurrence?.let { "Every ${if (it.interval > 1) "${it.interval} " else ""}${it.frequency.name.lowercase().removeSuffix("ly").let { f -> if (f == "dai") "day" else f }}${if (it.interval > 1) "s" else ""} from ${it.start}" } ?: d.date?.let { "On $it" },
    d.accountId?.let { "From ${account(it)}" },
    if (d.postsTransaction) "Adds the transaction automatically" else null,
)

private fun app.centsible.core.model.Schedule.asInput(): Array<Pair<String, Any?>> = arrayOf(
    "name" to name, "payee_id" to payeeId?.raw, "account_id" to accountId?.raw, "amount" to amount,
    "amount_rule" to amountOp.name.lowercase(), "amount_max" to amountMax,
    "repeats" to recurrence?.let { r ->
        j(
            "frequency" to r.frequency.name.lowercase(), "interval" to r.interval, "start" to r.start,
            "end_after" to r.endOccurrences.takeIf { r.endMode == EndMode.AfterOccurrences }, "end_on" to r.endDate.takeIf { r.endMode == EndMode.OnDate },
            "skip_weekend" to r.skipWeekend, "weekend_moves_earlier" to r.weekendBefore,
        )
    },
    "date" to date.takeIf { recurrence == null }, "posts_automatically" to postsTransaction,
)

internal fun ToolsBuilder.recurringWrites(gw: Gateways) {
    val G = ToolGroup.Recurring

    write(
        G, "create_schedule", "Schedule a bill, subscription or income so it shows in the forecast and upcoming bills.",
        schema { scheduleFields() },
        preview = { a, ctx -> Preview("New scheduled ${if (a.money("amount").isNegative) "bill" else "income"}", ctx.names.describe(parseSchedule(a))) },
    ) { a, ctx ->
        val s = gw.planning.createSchedule(ctx.budget, parseSchedule(a))
        done("Scheduled it", call("delete_schedule", "id" to s.id), "schedule" to ctx.names.encode(s))
    }

    write(
        G, "update_schedule", "Change a schedule (give its full details; they replace what's there).",
        schema {
            string("id", "The schedule.", required = true)
            scheduleFields()
        },
        preview = { a, ctx -> Preview("Change a schedule", ctx.names.describe(parseSchedule(a))) },
    ) { a, ctx ->
        val before = gw.planning.schedules(ctx.budget, 1).firstOrNull { it.id == a.string("id") } ?: throw ToolInputException("No schedule ${a.string("id")}")
        gw.planning.updateSchedule(ctx.budget, before.id, parseSchedule(a))
        done("Updated the schedule", call("update_schedule", "id" to before.id, *before.asInput()))
    }

    write(
        G, "delete_schedule", "Delete a schedule (its past transactions stay).",
        schema { string("id", "The schedule.", required = true) },
        preview = { a, ctx ->
            val s = gw.planning.schedules(ctx.budget, 1).firstOrNull { it.id == a.string("id") } ?: throw ToolInputException("No schedule ${a.string("id")}")
            Preview("Delete a schedule", listOf("${s.name ?: ctx.names.payee(s.payeeId) ?: "Scheduled"} · ${s.amount.text()}"))
        },
    ) { a, ctx ->
        val s = gw.planning.schedules(ctx.budget, 1).firstOrNull { it.id == a.string("id") } ?: throw ToolInputException("No schedule ${a.string("id")}")
        gw.planning.deleteSchedule(ctx.budget, s.id)
        done("Deleted the schedule", call("create_schedule", *s.asInput()))
    }

    write(
        G, "skip_schedule", "Skip a schedule's next date (this time it won't happen, or was paid another way).",
        schema { string("id", "The schedule.", required = true) },
        preview = { a, ctx -> Preview("Skip the next one", listOf(gw.planning.schedules(ctx.budget, 1).firstOrNull { it.id == a.string("id") }?.let { "${it.name ?: ctx.names.payee(it.payeeId)} on ${it.nextDate}" } ?: a.string("id"))) },
    ) { a, ctx ->
        gw.planning.skipSchedule(ctx.budget, a.string("id"))
        done("Skipped")
    }

    write(
        G, "post_schedule", "Add a schedule's next transaction now, before its date (it was paid early).",
        schema { string("id", "The schedule.", required = true) },
        preview = { a, ctx -> Preview("Add the transaction now", listOf(gw.planning.schedules(ctx.budget, 1).firstOrNull { it.id == a.string("id") }?.let { "${it.name ?: ctx.names.payee(it.payeeId)} · ${it.amount.text()}" } ?: a.string("id"))) },
    ) { a, ctx ->
        gw.planning.postSchedule(ctx.budget, a.string("id"))
        done("Added it")
    }

    write(
        G, "dismiss_subscription", "Stop suggesting a merchant as an unscheduled subscription.",
        schema { string("payee_id", "The merchant.", required = true) },
        preview = { a, ctx -> Preview("Not a subscription", listOf(ctx.names.requirePayee(a.payee("payee_id")))) },
    ) { a, ctx ->
        gw.insights.dismissSubscription(ctx.budget, a.payee("payee_id"))
        done("Dismissed")
    }
}

// ── Goals and plans ──

private fun parseAutomation(a: Args): app.centsible.core.model.Automation {
    fun cap(x: Args?) = x?.let { app.centsible.core.model.Automation.Cap(it.money("amount"), it.bool("hold", false), it.optEnum("period", app.centsible.core.model.Automation.CapPeriod.entries.toTypedArray()) ?: app.centsible.core.model.Automation.CapPeriod.Monthly, it.optString("start")) }
    fun adj(x: Args?) = x?.let { if (it.has("percent")) app.centsible.core.model.Automation.Adjustment.Percent(it.double("percent")) else app.centsible.core.model.Automation.Adjustment.Fixed(it.money("amount")) }
    val p = a.optInt("priority") ?: 1
    val d = a.optString("description")
    return when (a.string("type")) {
        "fixed" -> app.centsible.core.model.Automation.Fixed(p, a.optMoney("monthly"), cap(a.obj("cap")), d)
        "periodic" -> app.centsible.core.model.Automation.Periodic(p, a.money("amount"), a.enum("unit", app.centsible.core.model.Automation.PeriodUnit.entries.toTypedArray()), a.optInt("every") ?: 1, a.date("starting"), cap(a.obj("cap")), d)
        "save_by" -> app.centsible.core.model.Automation.SaveBy(p, a.money("amount"), a.month("month"), a.optInt("repeat_every")?.let { app.centsible.core.model.Automation.Repeat(a.bool("repeat_yearly", false), it) }, a.optMonth("spend_from"), d)
        "cover_schedule" -> app.centsible.core.model.Automation.CoverSchedule(p, a.string("schedule"), a.bool("full", false), adj(a.obj("adjustment")), d)
        "average" -> app.centsible.core.model.Automation.Average(p, a.int("months"), adj(a.obj("adjustment")), d)
        "copy" -> app.centsible.core.model.Automation.Copy(p, a.int("months_ago"), d)
        "percent_of_income" -> app.centsible.core.model.Automation.PercentOfIncome(p, a.double("percent"), a.optString("of") ?: app.centsible.core.model.Automation.ALL_INCOME, a.bool("previous_month", false), d)
        "refill" -> app.centsible.core.model.Automation.Refill(p, cap(a.obj("cap")) ?: throw ToolInputException("refill needs a cap"), d)
        "remainder" -> app.centsible.core.model.Automation.Remainder(a.optDouble("weight") ?: 1.0, cap(a.obj("cap")), d)
        "goal" -> app.centsible.core.model.Automation.Goal(a.money("amount"), d)
        else -> throw ToolInputException("Unknown automation type ${a.string("type")}")
    }
}

internal fun ToolsBuilder.planWrites(gw: Gateways) {
    val G = ToolGroup.Plans

    write(
        G, "set_savings_goal",
        "Set a category's savings goal: reach a balance (kind balance), or save an amount by a month (kind by, with target_month). Or clear it with clear: true.",
        schema {
            string("category_id", "The category.", required = true)
            enum("kind", listOf("balance", "by"), "Reach a balance, or save by a month.")
            amount("target", "The target amount.")
            string("target_month", "With kind by: YYYY-MM.")
            boolean("clear", "Remove the goal.")
        },
        preview = { a, ctx ->
            val name = ctx.names.requireCategory(a.category("category_id"))
            Preview("Savings goal", listOf(if (a.bool("clear", false)) "$name: remove the goal" else "$name: ${a.money("target").text()}${a.optMonth("target_month")?.let { " by ${monthName(it)}" } ?: ""}"))
        },
    ) { a, ctx ->
        val id = a.category("category_id")
        val before = gw.planAhead.goals(ctx.budget).firstOrNull { it.categoryId == id }
        val input = if (a.bool("clear", false)) null else app.centsible.core.model.GoalInput(
            if (a.optString("kind") == "by") app.centsible.core.model.Goal.Kind.By else app.centsible.core.model.Goal.Kind.Balance,
            a.money("target"),
            a.optMonth("target_month"),
        )
        gw.planAhead.setGoal(ctx.budget, id, input)
        val undo = if (before == null) call("set_savings_goal", "category_id" to id.raw, "clear" to true)
        else call("set_savings_goal", "category_id" to id.raw, "kind" to if (before.kind == app.centsible.core.model.Goal.Kind.By) "by" else "balance", "target" to before.target, "target_month" to before.targetMonth?.raw)
        done(if (input == null) "Removed the goal" else "Saved the goal", undo)
    }

    write(
        G, "save_target",
        "Create or change (with id) a target: kind account (grow account_id to amount, optionally by target_month), spend_under (keep category_id's spending under amount each month), or spend_at_least (put at least amount, or percent_of_income, into category_id each month).",
        schema {
            string("id", "An existing target to change.")
            enum("kind", listOf("account", "spend_under", "spend_at_least"), "What kind.", required = true)
            string("name", "A name.")
            string("account_id", "For kind account.")
            string("category_id", "For the spend kinds.")
            amount("amount", "The amount.")
            number("percent_of_income", "For spend_at_least: a share of the month's income, in percent.")
            string("target_month", "For kind account: reach it by YYYY-MM.")
        },
        preview = { a, ctx ->
            Preview(
                if (a.has("id")) "Change a target" else "New target",
                listOfNotNull(
                    a.optString("name"),
                    when (a.string("kind")) {
                        "account" -> "Grow ${ctx.names.requireAccount(a.account("account_id")).name} to ${a.money("amount").text()}${a.optMonth("target_month")?.let { " by ${monthName(it)}" } ?: ""}"
                        "spend_under" -> "Keep ${ctx.names.requireCategory(a.category("category_id"))} under ${a.money("amount").text()} a month"
                        else -> "Put at least ${a.optDouble("percent_of_income")?.let { "$it% of income" } ?: a.money("amount").text()} into ${ctx.names.requireCategory(a.category("category_id"))} each month"
                    },
                ),
            )
        },
    ) { a, ctx ->
        val kind = when (a.string("kind")) {
            "account" -> app.centsible.core.model.Target.Kind.Account
            "spend_under" -> app.centsible.core.model.Target.Kind.SpendUnder
            "spend_at_least" -> app.centsible.core.model.Target.Kind.SpendAtLeast
            else -> throw ToolInputException("kind should be account, spend_under or spend_at_least")
        }
        val id = a.optString("id")
        val before = id?.let { i -> gw.planAhead.targets(ctx.budget).firstOrNull { it.id == i } ?: throw ToolInputException("No target $i") }
        gw.planAhead.saveTarget(ctx.budget, id, app.centsible.core.model.TargetInput(kind, a.optString("name"), a.optAccount("account_id"), a.optCategory("category_id"), a.optMoney("amount"), a.optDouble("percent_of_income"), a.optMonth("target_month")))
        val undo = before?.let { b ->
            call(
                "save_target", "id" to b.id, "kind" to when (b.kind) {
                    app.centsible.core.model.Target.Kind.Account -> "account"
                    app.centsible.core.model.Target.Kind.SpendUnder -> "spend_under"
                    app.centsible.core.model.Target.Kind.SpendAtLeast -> "spend_at_least"
                },
                "name" to b.name, "account_id" to b.accountId?.raw, "category_id" to b.categoryId?.raw, "amount" to b.amount, "percent_of_income" to b.percentOfIncome, "target_month" to b.targetMonth?.raw,
            )
        }
        done(if (before == null) "Created the target" else "Updated the target", undo)
    }

    write(
        G, "delete_target", "Delete a target the person no longer wants to track.",
        schema { string("id", "The target.", required = true) },
        preview = { a, ctx -> Preview("Delete a target", listOf(gw.planAhead.targets(ctx.budget).firstOrNull { it.id == a.string("id") }?.name ?: a.string("id"))) },
    ) { a, ctx ->
        gw.planAhead.deleteTarget(ctx.budget, a.string("id"))
        done("Deleted the target")
    }

    write(
        G, "set_yearly_budget",
        "Budget a category by the year: a yearly amount spread a twelfth a month (null amount stops it). start_month is when the budget year starts (1-12, default 1).",
        schema {
            string("category_id", "The category.", required = true)
            number("amount", "The yearly amount, or null to stop.")
            integer("start_month", "First month of the budget year, 1-12.", min = 1, max = 12)
        },
        preview = { a, ctx -> Preview("Yearly budget", listOf("${ctx.names.requireCategory(a.category("category_id"))}: ${a.optMoney("amount")?.let { "${it.text()} a year" } ?: "stop budgeting by the year"}")) },
    ) { a, ctx ->
        val id = a.category("category_id")
        val before = gw.planAhead.annualBudgets(ctx.budget, ctx.thisMonth()).firstOrNull { it.categoryId == id }
        gw.planAhead.setAnnualBudget(ctx.budget, id, a.optMoney("amount"), a.optInt("start_month") ?: 1)
        done("Saved", call("set_yearly_budget", "category_id" to id.raw, "amount" to before?.amount, "start_month" to (before?.startMonth ?: 1)))
    }

    write(
        G, "set_budget_automations",
        "Replace a category's budget automations. Each has a type: fixed {monthly, cap?}, periodic {amount, every, unit day|week|month|year, starting}, save_by {amount, month, repeat_every?, repeat_yearly?}, cover_schedule {schedule (name), full?}, average {months}, copy {months_ago}, percent_of_income {percent, of: \"all income\"|\"available funds\"|income category id, previous_month?}, refill {cap}, remainder {weight}, goal {amount}; with priority (lower runs first) and description. cap is {amount, hold?, period?}. An empty list clears them.",
        schema {
            string("category_id", "The category.", required = true)
            month(what = "Month to project amounts for (optional).")
            objects("automations", "The automations, replacing what's there.", required = true) {
                string("type", "The kind.", required = true)
                integer("priority", "Lower runs first (default 1).")
                any("monthly", "fixed: the monthly amount.")
                any("amount", "The amount (periodic, save_by, goal).")
                any("cap", "{amount, hold, period}")
                any("every", "periodic: how many units.")
                any("unit", "periodic: day, week, month or year.")
                any("starting", "periodic: start date.")
                any("month", "save_by: by this month.")
                any("repeat_every", "save_by: repeat every N.")
                any("repeat_yearly", "save_by: repeat in years.")
                any("spend_from", "save_by: spend from this month.")
                any("schedule", "cover_schedule: the schedule's name.")
                any("full", "cover_schedule: budget it in full when due.")
                any("adjustment", "{percent} or {amount}")
                any("months", "average: how many months.")
                any("months_ago", "copy: from how many months ago.")
                any("percent", "percent_of_income: the percent.")
                any("of", "percent_of_income: what of.")
                any("previous_month", "percent_of_income: last month's income.")
                any("weight", "remainder: its weight.")
                string("description", "A note.")
            }
        },
        preview = { a, ctx -> Preview("Budget automations", listOf(ctx.names.requireCategory(a.category("category_id"))) + a.objects("automations").map { parseAutomation(it).encode().toString() }.ifEmpty { listOf("Remove them all") }) },
    ) { a, ctx ->
        val id = a.category("category_id")
        val before = gw.automations.get(ctx.budget, id, null)
        val r = gw.automations.set(ctx.budget, id, a.objects("automations").map(::parseAutomation), a.optMonth("month"))
        val undo = if (before.source == app.centsible.core.model.AutomationSource.Notes) null
        else call("set_budget_automations", "category_id" to id.raw, "automations" to before.automations.filter { it !is app.centsible.core.model.Automation.Unreadable }.map { it.encode() })
        done("Saved ${r.automations.size} automations", undo, "projected" to r.projected)
    }

    write(
        G, "set_income_settings",
        "What counts as income in the Income view: all inflows (mode all) or only some income categories (mode categories with category_ids).",
        schema {
            enum("mode", listOf("all", "categories"), "All inflows or chosen categories.", required = true)
            strings("category_ids", "With categories: which ones.")
        },
        preview = { a, ctx -> Preview("Income settings", listOf(if (a.string("mode") == "all") "Count all inflows" else "Count " + a.strings("category_ids").map { ctx.names.requireCategory(CategoryId(it)) }.joinToString())) },
    ) { a, ctx ->
        val before = gw.planAhead.incomeOverview(ctx.budget)
        gw.planAhead.setIncomeSettings(ctx.budget, if (a.string("mode") == "all") app.centsible.core.model.IncomeMode.All else app.centsible.core.model.IncomeMode.Categories, a.optStrings("category_ids").orEmpty().map(::CategoryId))
        done("Saved", call("set_income_settings", "mode" to before.mode.name.lowercase(), "category_ids" to before.categoryIds.map { it.raw }))
    }

    write(
        G, "save_pay_stub",
        "Record a pay stub (or change one with id): date, gross, deductions (name, kind tax|retirement|benefit|other, amount), and optionally employer, hours and hourly rate.",
        schema {
            string("id", "An existing stub to change.")
            string("date", "Pay date, YYYY-MM-DD.", required = true)
            string("employer", "Employer.")
            number("hours", "Hours worked.")
            amount("hourly_rate", "Hourly rate.")
            amount("gross", "Gross pay.", required = true)
            objects("deductions", "Each deduction.", required = true) {
                string("name", "Its name.", required = true)
                enum("kind", app.centsible.core.model.DeductionKind.entries.map { it.wire }, "Which kind.", required = true)
                amount("amount", "How much (positive).", required = true)
            }
            string("notes", "Notes.")
        },
        preview = { a, _ ->
            val ded = a.objects("deductions").sumOf { it.money("amount").minor }
            Preview("Pay stub · ${a.date("date")}", listOf("Gross ${a.money("gross").text()}", "Deductions ${Money(ded).text()}", "Net ${Money(a.money("gross").minor - ded).text()}"))
        },
    ) { a, ctx ->
        val input = app.centsible.core.model.PayStubInput(
            a.date("date"), a.optString("employer"), a.optDouble("hours"), a.optMoney("hourly_rate"), a.money("gross"),
            a.objects("deductions").map { app.centsible.core.model.PayStubDeduction(it.string("name"), app.centsible.core.model.DeductionKind.of(it.string("kind")), it.money("amount")) },
            a.optString("notes"),
        )
        val s = gw.planAhead.savePayStub(ctx.budget, a.optString("id"), input)
        done("Saved the pay stub", if (a.has("id")) null else call("delete_pay_stub", "id" to s.id))
    }

    write(
        G, "delete_pay_stub", "Delete a pay stub recorded by mistake.",
        schema { string("id", "The stub.", required = true) },
        preview = { a, _ -> Preview("Delete a pay stub", listOf(a.string("id"))) },
    ) { a, ctx ->
        gw.planAhead.deletePayStub(ctx.budget, a.string("id"))
        done("Deleted")
    }

    write(
        G, "save_mortgage",
        "Set up a mortgage or other fixed-rate loan (or change one with id): name, principal, yearly rate in percent, term in months, first payment date, and optional escrow, extra payment each month, paying account and the loan's balance account.",
        schema {
            string("id", "An existing mortgage to change.")
            string("name", "A name.", required = true)
            amount("principal", "What was borrowed.", required = true)
            number("rate_pct", "Yearly interest rate, in percent.", required = true)
            integer("term_months", "Term in months.", required = true, min = 1)
            string("first_payment", "First payment date, YYYY-MM-DD.", required = true)
            amount("escrow", "Escrow each month.")
            amount("extra", "Extra principal each month.")
            string("payment_account_id", "The account it's paid from.")
            string("loan_account_id", "The account tracking what's owed.")
            amount("home_value", "The home's value.")
        },
        preview = { a, _ -> Preview(if (a.has("id")) "Change a mortgage" else "New mortgage", listOfNotNull(a.string("name"), "${a.money("principal").text()} at ${a.double("rate_pct")}% over ${a.int("term_months")} months from ${a.date("first_payment")}", a.optMoney("extra")?.let { "Extra ${it.text()} a month" })) },
    ) { a, ctx ->
        gw.planAhead.saveMortgage(
            ctx.budget, a.optString("id"),
            app.centsible.core.model.MortgageInput(
                a.string("name"), a.money("principal"), a.double("rate_pct"), a.int("term_months"), a.date("first_payment"),
                a.optMoney("escrow") ?: Money.Zero, a.optMoney("extra") ?: Money.Zero, null, a.optAccount("payment_account_id"), a.optAccount("loan_account_id"),
                homeValue = a.optMoney("home_value"),
            ),
        )
        done("Saved the mortgage")
    }

    write(
        G, "set_home_value", "Update a mortgage's home value (for equity and net worth).",
        schema {
            string("id", "The mortgage.", required = true)
            amount("value", "The home's value.", required = true)
        },
        preview = { a, _ -> Preview("Home value", listOf(a.money("value").text())) },
    ) { a, ctx ->
        val before = gw.planAhead.mortgage(ctx.budget, a.string("id")).homeValue
        gw.planAhead.setHomeValue(ctx.budget, a.string("id"), a.money("value"))
        done("Saved", before?.let { call("set_home_value", "id" to a.string("id"), "value" to it) })
    }
}

// ── Accounts ──

internal fun ToolsBuilder.accountWrites(gw: Gateways) {
    val G = ToolGroup.Accounts
    val types = AccountType.entries.map { it.api }

    write(
        G, "create_account", "Add an account (for one that isn't bank-synced, or an off-budget asset or debt).",
        schema {
            string("name", "Its name.", required = true)
            enum("type", types, "What kind.")
            boolean("on_budget", "On budget (default true): its money is part of the budget.")
            amount("starting_balance", "Its balance today (negative for a debt).")
        },
        preview = { a, _ -> Preview("New account", listOfNotNull(a.string("name"), a.optString("type"), if (a.bool("on_budget", true)) "On budget" else "Off budget", a.optMoney("starting_balance")?.let { "Starting balance ${it.text()}" })) },
    ) { a, ctx ->
        val acc = gw.engine.createAccount(ctx.budget, a.string("name"), !a.bool("on_budget", true), a.optMoney("starting_balance") ?: Money.Zero, AccountType.fromApi(a.optString("type")))
        done("Added ${acc.name}", call("close_account", "account_id" to acc.id.raw), "account_id" to acc.id.raw)
    }

    write(
        G, "rename_account", "Rename an account, for example to tell two cards apart.",
        schema {
            string("account_id", "The account.", required = true)
            string("name", "Its new name.", required = true)
        },
        preview = { a, ctx -> Preview("Rename an account", listOf("${ctx.names.requireAccount(a.account("account_id")).name} → ${a.string("name")}")) },
    ) { a, ctx ->
        val old = ctx.names.requireAccount(a.account("account_id")).name
        gw.engine.renameAccount(ctx.budget, a.account("account_id"), a.string("name"))
        done("Renamed", call("rename_account", "account_id" to a.string("account_id"), "name" to old))
    }

    write(
        G, "set_account_type", "Set an account's type (or null to let the app guess).",
        schema {
            string("account_id", "The account.", required = true)
            nullableString("type", "One of ${types.joinToString()}, or null.", required = true)
        },
        preview = { a, ctx -> Preview("Account type", listOf("${ctx.names.requireAccount(a.account("account_id")).name}: ${a.optString("type") ?: "automatic"}")) },
    ) { a, ctx ->
        val old = ctx.names.requireAccount(a.account("account_id")).type
        gw.engine.setAccountType(ctx.budget, a.account("account_id"), a.optString("type")?.let { AccountType.fromApi(it) ?: throw ToolInputException("type should be one of ${types.joinToString()}") })
        done("Saved", call("set_account_type", "account_id" to a.string("account_id"), "type" to (old?.api ?: ExplicitNull)))
    }

    write(
        G, "move_account_budget",
        "Move an account off budget (tracked only: investments, mortgages, loans) or back on budget, in Actual. Off budget, its transfers with budgeted accounts count as spending: transfer_category_id fills the ones without a category. On budget, those transfers lose their categories.",
        schema {
            string("account_id", "The account.", required = true)
            boolean("off_budget", "True to track it off budget, false to budget it.", required = true)
            string("transfer_category_id", "Off budget: a spending category for its uncategorized transfers (contributions, payments).")
        },
        preview = { a, ctx ->
            val acct = ctx.names.requireAccount(a.account("account_id"))
            val off = a.bool("off_budget", true)
            val p = gw.accounts.budgetSwitch(ctx.budget, acct.id)
            val open = p.transfers - p.categorized
            Preview(
                if (off) "Move ${acct.name} off budget" else "Move ${acct.name} on budget",
                listOfNotNull(
                    if (off == p.offBudget) "It's already ${if (off) "off" else "on"} budget" else null,
                    if (off && open > 0) "$open transfers from budgeted accounts: ${a.optCategory("transfer_category_id")?.let { ctx.names.category(it) } ?: "left uncategorized"}" else null,
                    if (!off && p.categorized > 0) "${p.categorized} transfers lose their categories" else null,
                    if (!off && p.uncategorized > 0) "${p.uncategorized} of its transactions will need a category" else null,
                ),
            )
        },
    ) { a, ctx ->
        val off = a.bool("off_budget", true)
        val r = gw.accounts.switchBudget(ctx.budget, a.account("account_id"), off, if (off) a.optCategory("transfer_category_id") else null)
        done(
            if (off) "Off budget; categorized ${r.categorized} transfers" else "On budget; cleared ${r.cleared} transfer categories",
            // Back on budget clears exactly what moving it off filled in; the other way can't restore each transfer's old category.
            if (off) call("move_account_budget", "account_id" to a.string("account_id"), "off_budget" to false) else null,
        )
    }

    write(
        G, "close_account", "Close an account. If it has a balance, move it to another account (and, for on-budget money, a category).",
        schema {
            string("account_id", "The account.", required = true)
            string("move_balance_to_account_id", "Where its balance goes.")
            string("balance_category_id", "The category for that move, when needed.")
        },
        preview = { a, ctx -> Preview("Close an account", listOfNotNull(ctx.names.requireAccount(a.account("account_id")).name, a.optAccount("move_balance_to_account_id")?.let { "Its balance moves to ${ctx.names.requireAccount(it).name}" })) },
    ) { a, ctx ->
        val r = gw.engine.closeAccount(ctx.budget, a.account("account_id"), a.optAccount("move_balance_to_account_id"), a.optCategory("balance_category_id"))
        done(if (r == null) "Removed the empty account" else "Closed it", r?.let { call("reopen_account", "account_id" to a.string("account_id")) })
    }

    write(
        G, "reopen_account", "Reopen a closed account so it shows and can take transactions again.",
        schema { string("account_id", "The account.", required = true) },
        preview = { a, ctx -> Preview("Reopen an account", listOf(ctx.names.requireAccount(a.account("account_id")).name)) },
    ) { a, ctx ->
        gw.engine.reopenAccount(ctx.budget, a.account("account_id"))
        done("Reopened", call("close_account", "account_id" to a.string("account_id")))
    }

    write(
        G, "reconcile_account",
        "Reconcile an account to a statement balance: locks cleared transactions; with create_adjustment, adds a transaction for any difference.",
        schema {
            string("account_id", "The account.", required = true)
            amount("statement_balance", "The statement's balance.", required = true)
            boolean("create_adjustment", "Add a transaction for any difference.")
        },
        preview = { a, ctx ->
            val s = gw.accounts.reconcileStatus(ctx.budget, a.account("account_id"))
            Preview("Reconcile ${ctx.names.requireAccount(a.account("account_id")).name}", listOf("Statement ${a.money("statement_balance").text()} · cleared ${s.cleared.text()}", "Difference ${(a.money("statement_balance") - s.cleared).text()}"))
        },
    ) { a, ctx ->
        val r = gw.accounts.reconcile(ctx.budget, a.account("account_id"), a.money("statement_balance"), a.bool("create_adjustment", false))
        done(if (r.reconciled) "Reconciled ${r.lockedCount} transactions" else "Not reconciled: off by ${r.difference.text()}", null, "difference" to r.difference)
    }
}
