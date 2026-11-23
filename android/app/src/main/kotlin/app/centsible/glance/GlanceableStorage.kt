package app.centsible.glance

import app.centsible.core.model.CategoryGroupId
import app.centsible.core.model.CategoryId
import app.centsible.core.model.SankeyGraph
import app.centsible.core.model.SankeyLink
import app.centsible.core.model.SankeyNode
import android.content.Context
import app.centsible.core.domain.Glanceable
import app.centsible.core.domain.GlanceableStore
import app.centsible.core.model.Money
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The widgets' copy of the budget, in the app's private storage (never in backups: the app
 * opts out of them). Widgets and tiles read it so they draw instantly, even offline.
 */
@Singleton
class PrefsGlanceableStore @Inject constructor(@ApplicationContext context: Context) : GlanceableStore {
    private val prefs = context.getSharedPreferences("glanceables", Context.MODE_PRIVATE)

    override suspend fun read(): Glanceable? = withContext(Dispatchers.IO) {
        prefs.getString(KEY, null)?.let { runCatching { decode(JSONObject(it)) }.getOrNull() }
    }

    override suspend fun write(value: Glanceable?) = withContext(Dispatchers.IO) {
        prefs.edit().apply { if (value == null) remove(KEY) else putString(KEY, encode(value).toString()) }.apply()
    }

    override suspend fun lastSync(): Pair<Long, String>? = withContext(Dispatchers.IO) {
        val at = prefs.getLong(SYNC_AT, 0L)
        if (at == 0L) null else at to prefs.getString(SYNC_SUMMARY, "").orEmpty()
    }

    override suspend fun setLastSync(at: Long, summary: String) = withContext(Dispatchers.IO) {
        prefs.edit().putLong(SYNC_AT, at).putString(SYNC_SUMMARY, summary).apply()
    }

    private companion object {
        const val KEY = "glanceable"
        const val SYNC_AT = "sync_at"
        const val SYNC_SUMMARY = "sync_summary"

        fun encode(g: Glanceable) = JSONObject()
            .put("budgetName", g.budgetName ?: JSONObject.NULL)
            .put("month", g.month)
            .put("envelope", g.envelope)
            .put("toBudget", g.toBudget.minor)
            .put("spent", g.spent.minor)
            .put("budgeted", g.budgeted.minor)
            .put("reviewCount", g.reviewCount)
            .put("updatedAt", g.updatedAt)
            .put("locked", g.locked)
            .put("bills", JSONArray(g.bills.map { JSONObject().put("title", it.title).put("amount", it.amount.minor).put("date", it.date).put("when", it.whenText) }))
            .put("categories", JSONArray(g.categories.map { JSONObject().put("name", it.name).put("spent", it.spent.minor).put("budgeted", it.budgeted.minor).put("id", it.id ?: JSONObject.NULL).put("balance", it.balance.minor) }))
            .put("leftInBudget", g.leftInBudget.minor)
            .put("months", JSONArray(g.months.map { JSONObject().put("month", it.month).put("income", it.income.minor).put("spending", it.spending.minor) }))
            .put("netWorth", JSONArray(g.netWorth.map { JSONObject().put("month", it.month).put("value", it.value.minor) }))
            .put("sankey", g.sankey?.let(PrefsGlanceableStoreCodec::encodeSankey) ?: JSONObject.NULL)
            .put("trend", g.trend?.let { t ->
                JSONObject()
                    .put("monthToDate", JSONArray(t.monthToDate))
                    .put("lastMonth", JSONArray(t.lastMonth))
                    .put("daysInMonth", t.daysInMonth)
                    .put("week", JSONArray(t.week))
                    .put("weekdayToday", t.weekdayToday)
                    .put("lastWeekTotal", t.lastWeekTotal)
                    .put("weekAllowance", t.weekAllowance ?: JSONObject.NULL)
            } ?: JSONObject.NULL)

        fun decode(o: JSONObject): Glanceable {
            val bills = o.optJSONArray("bills") ?: JSONArray()
            return Glanceable(
                budgetName = if (o.isNull("budgetName")) null else o.getString("budgetName"),
                month = o.getString("month"),
                envelope = o.getBoolean("envelope"),
                toBudget = Money(o.getLong("toBudget")),
                spent = Money(o.getLong("spent")),
                budgeted = Money(o.getLong("budgeted")),
                reviewCount = o.getInt("reviewCount"),
                bills = (0 until bills.length()).map { i ->
                    val b = bills.getJSONObject(i)
                    Glanceable.Bill(b.getString("title"), Money(b.getLong("amount")), b.getString("date"), b.getString("when"))
                },
                updatedAt = o.getLong("updatedAt"),
                locked = o.optBoolean("locked", false),
                trend = o.optJSONObject("trend")?.let { t ->
                    fun longs(name: String) = t.getJSONArray(name).let { a -> (0 until a.length()).map { a.getLong(it) } }
                    Glanceable.Trend(
                        monthToDate = longs("monthToDate"),
                        lastMonth = longs("lastMonth"),
                        daysInMonth = t.getInt("daysInMonth"),
                        week = longs("week"),
                        weekdayToday = t.getInt("weekdayToday"),
                        lastWeekTotal = t.getLong("lastWeekTotal"),
                        weekAllowance = if (t.isNull("weekAllowance")) null else t.getLong("weekAllowance"),
                    )
                },
                categories = (o.optJSONArray("categories") ?: JSONArray()).let { a ->
                    (0 until a.length()).map { i ->
                        a.getJSONObject(i).let { c ->
                            Glanceable.CategorySlice(c.getString("name"), Money(c.getLong("spent")), Money(c.getLong("budgeted")), if (c.isNull("id")) null else c.optString("id"), Money(c.optLong("balance", 0)))
                        }
                    }
                },
                leftInBudget = Money(o.optLong("leftInBudget", 0)),
                months = (o.optJSONArray("months") ?: JSONArray()).let { a ->
                    (0 until a.length()).map { i -> a.getJSONObject(i).let { m -> Glanceable.MonthFlow(m.getString("month"), Money(m.getLong("income")), Money(m.getLong("spending"))) } }
                },
                netWorth = (o.optJSONArray("netWorth") ?: JSONArray()).let { a ->
                    (0 until a.length()).map { i -> a.getJSONObject(i).let { p -> Glanceable.NetWorthPoint(p.getString("month"), Money(p.getLong("value"))) } }
                },
                sankey = o.optJSONObject("sankey")?.let { runCatching { PrefsGlanceableStoreCodec.decodeSankey(it) }.getOrNull() },
            )
        }
    }
}

/** The money-flow graph as JSON, for the widget cache. */
internal object PrefsGlanceableStoreCodec {
    fun encodeSankey(g: SankeyGraph) = JSONObject()
        .put(
            "nodes",
            JSONArray(
                g.nodes.map { n ->
                    JSONObject().put("key", n.key).put("name", n.name).put("column", n.column).put("value", n.value).put("kind", n.kind.name)
                        .put("groupId", n.groupId?.raw ?: JSONObject.NULL).put("categoryId", n.categoryId?.raw ?: JSONObject.NULL)
                        .put("colorKey", n.colorKey ?: JSONObject.NULL)
                },
            ),
        )
        .put("links", JSONArray(g.links.map { JSONObject().put("from", it.from).put("to", it.to).put("value", it.value) }))

    fun decodeSankey(o: JSONObject): SankeyGraph {
        fun JSONObject.str(k: String) = if (isNull(k)) null else optString(k)
        val nodes = o.getJSONArray("nodes").let { a ->
            (0 until a.length()).map { i ->
                a.getJSONObject(i).let { n ->
                    SankeyNode(
                        n.getString("key"), n.getString("name"), n.getInt("column"), n.getLong("value"),
                        SankeyNode.Kind.valueOf(n.getString("kind")),
                        n.str("groupId")?.let(::CategoryGroupId), n.str("categoryId")?.let(::CategoryId), n.str("colorKey"),
                    )
                }
            }
        }
        val links = o.getJSONArray("links").let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).let { l -> SankeyLink(l.getString("from"), l.getString("to"), l.getLong("value")) } } }
        return SankeyGraph(nodes, links)
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class GlanceableModule {
    @Binds abstract fun store(impl: PrefsGlanceableStore): GlanceableStore
}
