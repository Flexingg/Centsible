package app.centsible.core.assistant

import app.centsible.core.domain.TransactionQuery
import app.centsible.core.model.AccountId
import app.centsible.core.model.AverageBasis
import app.centsible.core.model.ReviewPeriod
import app.centsible.core.model.TransferSearchOptions
import app.centsible.core.model.YearMonth
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray

private fun ToolContext.thisMonth() = YearMonth.of(now.year, now.monthValue)
private fun Args.monthOr(ctx: ToolContext, key: String = "month") = optMonth(key) ?: ctx.thisMonth()

/** Reading: everything the app shows, nothing changed. These run without asking. */
internal fun ToolsBuilder.readTools(gw: Gateways) {
    read(
        "get_overview",
        "The household's budgets, which one this chat is about, its currency and budget type (envelope or tracking), who you're talking to and their role, and what the server supports. Call it when you need to know which budget or what's possible; the chat's opening context already has most of this.",
    ) { _, ctx ->
        coroutineScope {
            val budgets = async { gw.engine.budgets() }
            val prefs = async { runCatching { gw.engine.preferences(ctx.budget) }.getOrNull() }
            val me = async { runCatching { gw.household.me() }.getOrNull() }
            val caps = async { runCatching { gw.engine.capabilities() }.getOrNull() }
            j(
                "today" to ctx.now.toString(),
                "budget_id" to ctx.budget.raw,
                "budgets" to budgets.await().map { j("id" to it.id.raw, "name" to it.name, "encrypted" to it.encrypted.takeIf { e -> e }) },
                "preferences" to prefs.await()?.let { p -> j("budget_type" to p.budgetType, "currency" to p.currencyCode, "first_day_of_week" to p.firstDayOfWeek) },
                "me" to me.await()?.let { m -> j("name" to m.member.displayName, "role" to m.member.role, "can_change_budget_data" to m.member.role.canWrite) },
                "server_features" to caps.await()?.let { c -> app.centsible.core.model.Feature.entries.filter { c.has(it) }.map { it.key } },
            )
        }
    }

    read(
        "list_accounts",
        "Every account with its balance, type (cash, savings, credit, investment, mortgage, loan), whether it's on budget, closed or bank-synced. Call this for balances, debts, savings and anything account-level.",
        schema { boolean("include_closed", "Also list closed accounts (default false).") },
    ) { a, ctx ->
        val all = ctx.names.get().accounts
        JsonArray(all.filter { a.bool("include_closed", false) || !it.closed }.map { it.encode() })
    }

    read(
        "get_categories",
        "The category tree: groups (level 1) and their categories, with sub-categories down to level 5, each with its id, level, parent, whether it's hidden, income, or a transfer kept off the books. Call it to find category ids, or to see how the budget is organized.",
        schema { boolean("include_hidden", "Also list hidden groups and categories (default false).") },
    ) { a, ctx ->
        val snap = ctx.names.get()
        val hidden = a.bool("include_hidden", false)
        JsonArray(
            snap.groups.filter { hidden || !it.hidden }.map { g ->
                j(
                    "id" to g.id,
                    "name" to g.name,
                    "income" to g.isIncome.takeIf { it },
                    "hidden" to g.hidden.takeIf { it },
                    "categories" to snap.tree.flatten(g.id).filter { (e, _) -> hidden || !e.hidden }.map { (e, level) ->
                        j(
                            "id" to e.id,
                            "name" to e.name,
                            "level" to level,
                            "parent_id" to e.parentId?.takeIf { snap.tree.parentOf(e.id) != null },
                            "hidden" to e.hidden.takeIf { it },
                            "transfer_off_books" to (e.id in snap.offBooks).takeIf { it },
                        )
                    },
                )
            },
        )
    }

    read(
        "search_transactions",
        "Find transactions. Filter by account, category (with its sub-categories unless own_only), group, merchant, dates, text in payee/notes/category, or only those still needing a category. Newest first, up to `limit` (default 50, at most 200); pass `cursor` from the result for the next page. Use it to answer questions about specific purchases, to check what a category's spending was made of, or to gather transactions before proposing changes.",
        schema {
            string("account_id", "Only this account.")
            string("category_id", "Only this category (and its sub-categories, unless own_only).")
            boolean("own_only", "With category_id: leave its sub-categories out.")
            string("group_id", "Only categories in this group.")
            string("payee_id", "Only this merchant.")
            string("since", "From this date, YYYY-MM-DD.")
            string("until", "Up to this date, YYYY-MM-DD.")
            string("text", "Search payee, notes and category name.")
            boolean("uncategorized", "Only on-budget transactions still waiting for a category.")
            integer("limit", "How many (default 50, max 200).", min = 1, max = 200)
            string("cursor", "From a previous result's next_cursor.")
        },
    ) { a, ctx ->
        val q = TransactionQuery(
            accountId = a.optAccount("account_id"),
            categoryId = a.optCategory("category_id"),
            withSubcategories = a.has("category_id") && !a.bool("own_only", false),
            groupId = a.optString("group_id")?.let { app.centsible.core.model.CategoryGroupId(it) },
            payeeId = a.optPayee("payee_id"),
            since = a.optDate("since"),
            until = a.optDate("until"),
            search = a.optString("text"),
            uncategorized = a.bool("uncategorized", false),
            limit = (a.optInt("limit") ?: 50).coerceIn(1, 200),
        )
        val page = gw.engine.transactions(ctx.budget, q, a.optString("cursor"))
        j(
            "count" to page.items.size,
            "total_of_these" to app.centsible.core.model.Money(page.items.sumOf { it.amount.minor }),
            "transactions" to page.items.map { ctx.names.encode(it) },
            "next_cursor" to page.nextCursor,
        )
    }

    read(
        "get_transaction",
        "One transaction in full, splits included.",
        schema { string("id", "The transaction id.", required = true) },
    ) { a, ctx -> ctx.names.encode(gw.engine.transaction(ctx.budget, a.transaction("id"))) }

    read(
        "get_budget_month",
        "A month of the budget: To Budget, income, and for every group and category what's budgeted, spent and left (balance, rollover included). Sub-categories are listed after their parent with `level` and `parent_id`; a parent's own figures plus `branch_*` totals that add its sub-categories. Call it for any question about budgets, what's left, or overspending.",
        schema {
            month()
            boolean("include_hidden", "Also include hidden groups and categories.")
        },
    ) { a, ctx -> ctx.names.encode(gw.engine.budgetMonth(ctx.budget, a.monthOr(ctx)), a.bool("include_hidden", false)) }

    read("list_budget_months", "The months the budget has data for.") { _, ctx -> JsonArray(gw.engine.budgetMonths(ctx.budget).map { toJson(it) }) }

    read(
        "get_category_history",
        "One category's budgeted and spent for each of the last 12 months up to `month`, with its 3, 6 and 12-month averages, highest and lowest. Call it before suggesting a budget amount.",
        schema {
            string("category_id", "The category.", required = true)
            month()
        },
    ) { a, ctx ->
        val h = gw.planAhead.categoryHistory(ctx.budget, a.category("category_id"), a.monthOr(ctx))
        j(
            "category" to ctx.names.category(h.categoryId),
            "months" to h.months.map { j("month" to it.month, "spent" to it.spent, "budgeted" to it.budgeted) },
            "average_3" to h.avg3, "average_6" to h.avg6, "average_12" to h.avg12,
            "highest" to h.highest, "lowest" to h.lowest, "months_of_history" to h.monthsOfHistory,
        )
    }

    read(
        "get_cash_flow",
        "Income, spending and net for each month in a range (spending is negative). Use it for trends and savings rate.",
        schema {
            string("start", "First month, YYYY-MM.", required = true)
            string("end", "Last month, YYYY-MM.", required = true)
        },
    ) { a, ctx ->
        JsonArray(gw.reports.cashFlow(ctx.budget, a.month("start"), a.month("end")).map { j("month" to it.month, "income" to it.income, "spending" to it.expenses, "net" to it.net) })
    }

    read(
        "get_spending_by_category",
        "Spending per category over a month range, biggest first (negative = spent). Each category's own spending; sub-categories appear separately.",
        schema {
            string("start", "First month, YYYY-MM.", required = true)
            string("end", "Last month, YYYY-MM.", required = true)
        },
    ) { a, ctx ->
        val r = gw.reports.spending(ctx.budget, a.month("start"), a.month("end"))
        j("total" to r.total, "categories" to r.categories.map { j("category" to it.name, "category_id" to it.categoryId, "group" to it.groupName, "amount" to it.amount) })
    }

    read(
        "get_money_flow",
        "Where the money came from and went over a month range: income by income category and spending by category, each with its group and parent (what the Money flow Sankey shows). Use it for 'where does my money go' questions across the whole hierarchy.",
        schema {
            string("start", "First month, YYYY-MM.", required = true)
            string("end", "Last month, YYYY-MM.", required = true)
        },
    ) { a, ctx ->
        val f = gw.reports.flows(ctx.budget, a.month("start"), a.month("end"))
        fun enc(list: List<app.centsible.core.model.FlowCategory>) = list.map { j("category" to it.name, "category_id" to it.categoryId, "group" to it.groupName, "parent_id" to it.parentId, "amount" to it.amount) }
        j("income_total" to f.totalIncome, "spending_total" to f.totalSpending, "income" to enc(f.income), "spending" to enc(f.spending))
    }

    read(
        "get_net_worth",
        "Net worth at each month end for the last `months` months (assets, liabilities, total), with every account's balance history. Includes off-budget accounts (investments, mortgage, home).",
        schema { integer("months", "How many months back (default 12, max 60).", min = 2, max = 60) },
    ) { a, ctx ->
        val nw = gw.insights.netWorth(ctx.budget, (a.optInt("months") ?: 12).coerceIn(2, 60))
        j(
            "months" to nw.points.map { j("month" to it.month, "assets" to it.assets, "liabilities" to it.liabilities, "net_worth" to it.netWorth) },
            "accounts" to nw.accounts.map { j("id" to it.id, "name" to it.name, "off_budget" to it.offBudget.takeIf { o -> o }, "closed" to it.closed.takeIf { c -> c }, "balances" to it.balances) },
        )
    }

    read(
        "get_daily_spending",
        "Spending for each day in a range (positive, refunds netted), at most 400 days. Use for pace, weekday patterns and no-spend streaks.",
        schema {
            string("from", "First day, YYYY-MM-DD.", required = true)
            string("to", "Last day, YYYY-MM-DD.", required = true)
        },
    ) { a, ctx -> JsonArray(gw.reports.dailySpending(ctx.budget, a.date("from"), a.date("to")).map { j("date" to it.date, "spent" to it.amount) }) }

    read(
        "get_insights",
        "A month's spending against the three months before it: projected month-end total, each category's trend, and alerts (categories running hot or cold, unusual charges, new merchants, price changes on bills). Call it for 'how am I doing this month' or 'anything I should know'.",
        schema { month() },
    ) { a, ctx ->
        val i = gw.insights.insights(ctx.budget, a.optMonth("month"))
        j(
            "month" to i.month, "days_elapsed" to i.daysElapsed, "days_in_month" to i.daysInMonth,
            "spent" to i.spent, "typical" to i.typical, "projected" to i.projected,
            "categories" to i.categories.map { j("category" to it.name, "category_id" to it.categoryId, "spent" to it.spent, "typical" to it.typical, "projected" to it.projected, "change_pct" to it.changePct) },
            "alerts" to i.alerts.map { j("kind" to it.kind, "severity" to it.severity, "title" to it.title, "detail" to it.detail, "amount" to it.amount, "date" to it.date, "category_id" to it.categoryId, "transaction_id" to it.transactionId) },
        )
    }

    read(
        "get_subscriptions",
        "Recurring payments found in the history that have no schedule yet (with their yearly cost), bills that vary but recur on a pattern, and price changes on existing scheduled bills. Call it for subscription audits.",
    ) { _, ctx ->
        val s = gw.insights.subscriptions(ctx.budget)
        j(
            "unscheduled" to s.candidates.map {
                j("payee" to it.payeeName, "payee_id" to it.payeeId, "account" to it.accountName, "amount" to it.amount, "approximate" to it.approximateAmount.takeIf { x -> x }, "repeats" to it.recurrence.encode(), "last_date" to it.lastDate, "yearly" to it.yearlyAmount, "income" to it.income.takeIf { x -> x })
            },
            "patterns" to s.patterns.map {
                j("payee" to it.payeeName, "payee_id" to it.payeeId, "account" to it.accountName, "income" to it.income.takeIf { x -> x }, "description" to it.description, "min" to it.min, "max" to it.max, "average_3" to it.average3, "next" to it.next?.let { n -> j("date" to n.date, "amount" to n.amount) })
            },
            "price_changes" to s.priceChanges.map { j("schedule_id" to it.scheduleId, "name" to it.name, "previous" to it.previous, "latest" to it.latest, "change_pct" to it.changePct, "date" to it.date) },
        )
    }

    read(
        "get_period_review",
        "A week's, month's, quarter's or year's story: income, spending, saved and savings rate, top categories and merchants, biggest purchase, busiest and quietest stretch, no-spend days, and the change from the period before. `date` is any day in the period (default today).",
        schema {
            enum("period", ReviewPeriod.entries.map { it.key }, "Which kind of period.", required = true)
            string("date", "Any day in the period, YYYY-MM-DD (default today).")
        },
    ) { a, ctx ->
        val r = gw.insights.review(ctx.budget, ReviewPeriod.of(a.string("period")), a.optDate("date"))
        j(
            "label" to r.label, "start" to r.start, "end" to r.end, "complete" to r.complete, "empty" to r.empty.takeIf { it },
            "income" to r.income, "spending" to r.spending, "saved" to r.saved, "savings_rate_pct" to r.savingsRate,
            "purchases" to r.purchases, "daily_average" to r.dailyAverage,
            "top_categories" to r.topCategories.map { j("category" to it.name, "amount" to it.amount, "share" to it.share) },
            "top_merchants" to r.topMerchants.map { j("merchant" to it.name, "amount" to it.amount, "visits" to it.visits) },
            "biggest_purchase" to r.biggestPurchase?.let { j("date" to it.date, "payee" to it.payeeName, "category" to it.categoryName, "amount" to it.amount) },
            "no_spend_days" to r.noSpendDays, "longest_no_spend_streak_days" to r.longestNoSpendStreak.days,
            "new_merchants" to r.newMerchants,
            "stretches" to r.buckets.map { j("label" to it.label, "spending" to it.spending, "income" to it.income) },
            "previous_period" to r.previousPeriod?.let { j("label" to it.label, "spending" to it.spending, "income" to it.income, "spending_change_pct" to it.spendingChangePct) },
        )
    }

    read(
        "list_recurring",
        "Scheduled bills, subscriptions and income (Actual's schedules) with amounts, how they repeat and their next dates. Call it for upcoming bills and fixed costs.",
        schema { integer("upcoming", "How many upcoming dates to list for each (default 3).", min = 1, max = 12) },
    ) { a, ctx -> JsonArray(gw.planning.schedules(ctx.budget, a.optInt("upcoming") ?: 3).map { ctx.names.encode(it) }) }

    read(
        "get_forecast",
        "Projected day-by-day balance of on-budget accounts (or the ones given) for the next `days` days from scheduled bills and income, plus typical everyday spending; the lowest point; and bills already paid this month. Call it for 'will I have enough' and cash-flow timing questions.",
        schema {
            integer("days", "How far ahead (default 60, max 365).", min = 7, max = 365)
            strings("account_ids", "Only these accounts (default: every on-budget account).")
            boolean("include_typical", "Include typical everyday spending (default true).")
        },
    ) { a, ctx ->
        val f = gw.planAhead.forecast(ctx.budget, (a.optInt("days") ?: 60).coerceIn(7, 365), a.optStrings("account_ids").orEmpty().map(::AccountId), a.bool("include_typical", true))
        // Weekly points keep a long forecast short; events and the low point are exact.
        val days = f.days.filterIndexed { i, _ -> f.days.size <= 62 || i % 7 == 0 || i == f.days.lastIndex }
        j(
            "from" to f.from, "to" to f.to, "starting_balance" to f.startingBalance, "typical_daily_spending" to f.typicalDaily,
            "lowest" to j("date" to f.lowest.date, "balance" to f.lowest.balance),
            "events" to f.events.map { j("date" to it.date, "name" to it.name, "account" to it.accountName, "amount" to it.amount, "overdue" to it.overdue.takeIf { x -> x }, "estimate" to it.estimate, "internal_transfer" to it.internalTransfer.takeIf { x -> x }) },
            "balances" to days.map { j("date" to it.date, "balance" to it.balance) },
            "paid_this_month" to f.paid.map { j("date" to it.date, "name" to it.name, "amount" to it.amount) },
        )
    }

    read(
        "list_rules",
        "The budget's rules (what Actual does to transactions as they come in: set categories, rename payees...). Rule amounts are in cents, as Actual stores them.",
    ) { _, ctx -> JsonArray(gw.planning.rules(ctx.budget).map { it.encode() }) }

    read(
        "preview_rule",
        "Dry-run a rule: which existing transactions it would match and what it would change. Nothing is written. Use it before proposing a new rule. Same shape as create_rule.",
        ruleSchema(),
    ) { a, ctx ->
        val p = gw.rules.preview(ctx.budget, parseRule(a), (a.optInt("limit") ?: 20).coerceIn(1, 50))
        j(
            "matches" to p.matchCount,
            "examples" to p.items.map { item -> j("transaction" to ctx.names.encode(item.transaction), "changes" to item.changes.map { j("field" to it.field, "to" to it.value, "note" to it.note, "error" to it.error) }) },
            "errors" to p.errors.takeIf { it.isNotEmpty() },
        )
    }

    read(
        "list_merchants",
        "Every merchant (payee) with how many transactions and rules use it. Transfer payees stand for accounts.",
    ) { _, ctx ->
        JsonArray(gw.planning.payeeStats(ctx.budget).map { j("id" to it.id, "name" to it.name, "transactions" to it.transactionCount, "rules" to it.ruleCount, "transfer_account_id" to it.transferAccountId) })
    }

    read("list_tags", "Tags (#words in transaction notes) with their colors and descriptions.") { _, ctx ->
        JsonArray(gw.planning.tags(ctx.budget).map { j("id" to it.id, "tag" to it.tag, "color" to it.color, "description" to it.description) })
    }

    read(
        "get_category_note",
        "A category's notes (where Actual keeps #template and #goal lines).",
        schema { string("category_id", "The category.", required = true) },
    ) { a, ctx -> j("category" to ctx.names.category(a.category("category_id")), "note" to gw.planning.categoryNote(ctx.budget, a.category("category_id"))) }

    read(
        "get_goals",
        "Savings goals (in categories' notes): target, balance, progress, what's needed each month, projected month and whether it's on track.",
        schema { month() },
    ) { a, ctx ->
        JsonArray(
            gw.planAhead.goals(ctx.budget, a.optMonth("month")).map {
                j(
                    "category" to it.name, "category_id" to it.categoryId, "kind" to if (it.kind == app.centsible.core.model.Goal.Kind.By) "save_by_month" else "reach_balance",
                    "target" to it.target, "target_month" to it.targetMonth, "balance" to it.balance, "progress" to it.progress, "remaining" to it.remaining,
                    "monthly_needed" to it.monthlyNeeded, "average_contribution" to it.avgContribution, "projected_month" to it.projectedMonth, "status" to it.status,
                )
            },
        )
    }

    read(
        "get_targets",
        "Targets the household set beyond Actual's goals: grow an account to an amount, keep a category's spending under an amount each month, or put at least an amount (or share of income) into a category. With progress, status and months kept.",
        schema { month() },
    ) { a, ctx ->
        JsonArray(
            gw.planAhead.targets(ctx.budget, a.optMonth("month")).map {
                j(
                    "id" to it.id, "kind" to it.kind, "name" to it.name, "account_id" to it.accountId, "category_id" to it.categoryId,
                    "amount" to it.amount, "percent_of_income" to it.percentOfIncome, "target_month" to it.targetMonth,
                    "current" to it.current, "goal_this_month" to it.goal, "progress" to it.progress, "status" to it.status, "remaining" to it.remaining,
                    "monthly_needed" to it.monthlyNeeded, "months_kept" to it.monthsKept, "projected_month" to it.projectedMonth,
                )
            },
        )
    }

    read(
        "get_budget_suggestions",
        "Autopilot: for every category, last month's spending, the 3, 6 and 12-month averages and the suggested budget; which categories are overspent this month; and the moves that would cover them. Call it before proposing budget amounts for a month.",
        schema { month() },
    ) { a, ctx ->
        val p = gw.planAhead.autopilot(ctx.budget, a.monthOr(ctx))
        j(
            "month" to p.month, "to_budget" to p.toBudget,
            "categories" to p.suggestions.map { s ->
                j(
                    "category" to s.name, "category_id" to s.categoryId, "group" to s.groupName, "budgeted" to s.budgeted, "last_month_spent" to s.lastMonthSpent,
                    "average" to AverageBasis.entries.associate { it.months.toString() to s.average[it] },
                    "suggested" to AverageBasis.entries.associate { it.months.toString() to s.suggested[it] },
                    "months_of_history" to s.monthsOfHistory,
                )
            },
            "overspent" to p.overspent.map { j("category" to it.name, "category_id" to it.categoryId, "amount" to it.amount) },
            "cover_plan" to p.coverMoves.map { j("from" to it.fromName, "to" to it.toName, "amount" to it.amount) },
            "left_uncovered" to p.uncovered,
        )
    }

    read(
        "get_yearly_budgets",
        "Categories budgeted by the year (a yearly amount spread a twelfth a month): the amount, where this month falls in the budget year, what's left and this month's suggestion.",
        schema { month() },
    ) { a, ctx ->
        JsonArray(
            gw.planAhead.annualBudgets(ctx.budget, a.monthOr(ctx)).map {
                j("category" to ctx.names.category(it.categoryId), "category_id" to it.categoryId, "yearly_amount" to it.amount, "start_month" to it.startMonth, "month_of_year" to it.monthIndex + 1, "remaining" to it.remaining, "budgeted" to it.budgeted, "suggested" to it.suggested)
            },
        )
    }

    read(
        "get_budget_automations",
        "Budget automations (Actual's programmable budgets: fixed amounts, save-by goals, averages, percent of income, refills...) for every category, or one, with what each would budget in `month`.",
        schema {
            string("category_id", "Just this category.")
            month()
        },
    ) { a, ctx ->
        val month = a.optMonth("month")
        val one = a.optCategory("category_id")
        if (one != null) {
            val c = gw.automations.get(ctx.budget, one, month)
            j("category" to ctx.names.category(one), "source" to c.source, "automations" to c.automations.map { it.encode() }, "projected" to c.projected, "notes_have_templates" to c.notesHaveTemplates.takeIf { it })
        } else {
            JsonArray(
                gw.automations.list(ctx.budget, month).filter { it.automations.isNotEmpty() }.map {
                    j("category" to it.name, "category_id" to it.categoryId, "group" to it.groupName, "source" to it.source, "automations" to it.automations.map { x -> x.encode() }, "projected" to it.projected)
                },
            )
        }
    }

    read(
        "get_income",
        "Income for a year: by month (with changes), totals, average and projection, plus pay stubs (gross, deductions by kind, net), effective tax and take-home rates, and projected yearly retirement and tax.",
        schema { integer("year", "The year (default this year).", min = 2000, max = 2100) },
    ) { a, ctx ->
        val o = gw.planAhead.incomeOverview(ctx.budget, a.optInt("year"))
        j(
            "year" to o.year, "counts" to o.mode, "income_categories" to o.categoryIds.takeIf { it.isNotEmpty() },
            "months" to o.months.map { j("month" to it.month, "amount" to it.amount, "deposits" to it.count, "change" to it.deltaFromPrev) },
            "summary" to o.summary.let { s -> j("year_to_date" to s.ytdTotal, "last_year" to s.prevYearTotal, "monthly_average" to s.monthlyAverage, "projected_year" to s.projectedAnnual, "variation_pct" to s.variationPercent) },
            "pay_stubs" to o.payStubs.map { p -> j("id" to p.id, "date" to p.date, "employer" to p.employer, "hours" to p.hours, "hourly_rate" to p.hourlyRate, "gross" to p.gross, "net" to p.net, "deductions" to p.deductions.map { d -> j("name" to d.name, "kind" to d.kind.wire, "amount" to d.amount) }, "notes" to p.notes) },
            "pay_stub_summary" to o.payStubSummary.let { s ->
                j(
                    "stubs" to s.stubCount, "gross" to s.totalGross, "net" to s.totalNet, "deductions" to s.totalDeductions,
                    "effective_tax_rate" to s.effectiveTaxRate, "take_home_rate" to s.takeHomeRate,
                    "projected_year_gross" to s.projectedAnnualGross, "projected_year_retirement" to s.projectedAnnualRetirement, "projected_year_tax" to s.projectedAnnualTax,
                )
            },
        )
    }

    read(
        "list_mortgages",
        "Mortgages and other fixed-rate loans: rate, payment, balance, payoff date, interest paid and left, how far ahead of schedule, home value and equity. Pass `id` for one with its full amortization schedule.",
        schema { string("id", "One mortgage, with its schedule.") },
    ) { a, ctx ->
        fun enc(m: app.centsible.core.model.Mortgage, rows: Boolean) = j(
            "id" to m.id, "name" to m.name, "principal" to m.principal, "rate_pct" to m.rate, "term_months" to m.termMonths, "first_payment" to m.firstPayment,
            "monthly_payment" to m.monthlyPayment, "monthly_total_with_escrow" to m.monthlyTotal, "escrow" to m.escrow, "extra_each_month" to m.extra,
            "balance" to m.balance, "scheduled_balance" to m.scheduledBalance, "ahead_by" to m.aheadBy, "payments_made" to m.paymentsMade, "payments_left" to m.paymentsLeft,
            "payoff_date" to m.payoffDate, "original_payoff_date" to m.originalPayoffDate, "interest_paid" to m.interestPaid, "interest_left" to m.interestLeft,
            "interest_saved" to m.interestSaved, "home_value" to m.homeValue, "equity" to m.equity,
            "schedule" to m.schedule.takeIf { rows }?.map { r -> j("n" to r.n, "date" to r.date, "payment" to r.payment, "interest" to r.interest, "principal" to r.principal, "extra" to r.extra, "balance" to r.balance, "paid_on" to r.paidOn) },
        )
        a.optString("id")?.let { enc(gw.planAhead.mortgage(ctx.budget, it), rows = true) } ?: JsonArray(gw.planAhead.mortgages(ctx.budget).map { enc(it, rows = false) })
    }

    read(
        "get_review_inbox",
        "Transactions waiting in this person's review inbox (new since they last caught up, plus anything uncategorized).",
        schema { integer("limit", "How many (default 50).", min = 1, max = 200) },
    ) { a, ctx ->
        val r = gw.transactions.inbox(ctx.budget, a.optInt("limit") ?: 50)
        j("total" to r.total, "more" to r.more.takeIf { it }, "since" to r.since, "transactions" to r.items.map { ctx.names.encode(it) })
    }

    read(
        "find_transfer_matches",
        "Pairs of transactions that look like one transfer seen from both accounts (a card payment from checking and the credit card's payment), not yet linked.",
        schema {
            boolean("all_time", "Look through all history, not just recent.")
            boolean("fuzzy", "Allow amounts that differ a little.")
        },
    ) { a, ctx ->
        val m = gw.transactions.transferMatches(ctx.budget, TransferSearchOptions(allTime = a.bool("all_time", false), fuzzy = a.bool("fuzzy", false)))
        j("auto_link_on" to m.auto, "pairs" to m.pairs.map { p -> j("from" to ctx.names.encode(p.from), "to" to ctx.names.encode(p.to), "days_apart" to p.days, "confident" to p.confident, "reason" to p.reason) })
    }

    read(
        "find_transfer_candidates",
        "Possible other sides for one transaction that should be a transfer.",
        schema {
            string("transaction_id", "The transaction.", required = true)
            string("text", "Narrow by payee or notes text.")
        },
    ) { a, ctx ->
        JsonArray(gw.transactions.transferCandidates(ctx.budget, a.transaction("transaction_id"), a.optString("text")).map { j("transaction" to ctx.names.encode(it.transaction), "exact_amount" to it.exact, "days_apart" to it.days) })
    }

    read("find_duplicates", "The same transaction twice in an account (often from reconnecting bank sync).") { _, ctx ->
        JsonArray(gw.transactions.duplicates(ctx.budget).map { d -> j("a" to ctx.names.encode(d.a), "b" to ctx.names.encode(d.b), "days_apart" to d.days) })
    }

    read(
        "get_reconcile_status",
        "An account's cleared, uncleared and total balance, for reconciling against a statement.",
        schema { string("account_id", "The account.", required = true) },
    ) { a, ctx ->
        val s = gw.accounts.reconcileStatus(ctx.budget, a.account("account_id"))
        j("account" to ctx.names.account(a.account("account_id")), "cleared" to s.cleared, "uncleared" to s.uncleared, "balance" to s.balance)
    }

    read("get_household", "Who's in the household, their roles and which budgets they can open.") { _, _ ->
        coroutineScope {
            val me = async { gw.household.me() }
            val members = async { runCatching { gw.household.members() }.getOrDefault(emptyList()) }
            j("me" to me.await().member.displayName, "members" to members.await().map { j("name" to it.displayName, "role" to it.role, "disabled" to it.disabled.takeIf { d -> d }) })
        }
    }

    read("get_server_status", "The bridge and Actual server: versions, updates available, connection, disk space and backups.") { _, _ ->
        coroutineScope {
            val s = async { gw.server.status() }
            val b = async { runCatching { gw.server.backups() }.getOrNull() }
            val st = s.await()
            j(
                "bridge_version" to st.bridgeVersion, "bridge_update_available" to st.bridgeUpdateAvailable.takeIf { it }, "actual_version" to st.actualVersion,
                "actual_connected" to st.actualConnected, "compatibility" to st.actualCompatibility, "disk_free_bytes" to st.diskFree,
                "backups" to b.await()?.let { o -> j("every_hours" to o.intervalHours, "keep" to o.keep, "last" to o.lastRunAt, "last_error" to o.lastError, "count" to o.items.size) },
            )
        }
    }
}
