package app.centsible.feature.reports

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.centsible.core.designsystem.component.Loadable
import app.centsible.core.designsystem.component.MoneyFormat
import app.centsible.core.domain.InsightsGateway
import app.centsible.core.domain.SelectedBudget
import app.centsible.core.domain.userMessage
import app.centsible.core.model.Money
import app.centsible.core.model.ReviewPeriod
import app.centsible.core.model.YearInReview
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class YearInReviewUiState(
    val period: ReviewPeriod = ReviewPeriod.Year,
    val data: Loadable<YearInReview> = Loadable.Loading,
)

@HiltViewModel
class YearInReviewViewModel @Inject constructor(
    private val insights: InsightsGateway,
    private val selectedBudget: SelectedBudget,
) : ViewModel() {
    private val state = MutableStateFlow(YearInReviewUiState())
    val uiState: StateFlow<YearInReviewUiState> = state.asStateFlow()

    /** [period] and [date] come from a "your month is wrapped" notification; otherwise the last finished year. */
    fun start(period: ReviewPeriod?, date: String?) {
        if (started) return
        started = true
        load(period ?: ReviewPeriod.Year, date)
    }
    private var started = false

    /** [date]: any day in the period; null for the last finished one. */
    fun load(period: ReviewPeriod, date: String?) = viewModelScope.launch {
        state.update { it.copy(period = period, data = Loadable.Loading) }
        val r = runCatching { insights.review(selectedBudget(), period, date) }
        if (state.value.period == period) state.update { it.copy(data = r.fold({ v -> Loadable.Ready(v) }, { e -> Loadable.Failed(e.userMessage()) })) }
    }
}

@Composable
fun YearInReviewRoute(onClose: () -> Unit, period: String? = null, date: String? = null, viewModel: YearInReviewViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.start(period?.let(ReviewPeriod::of), date) }
    YearInReviewScreen(state, onClose = onClose, onPeriod = { p, d -> viewModel.load(p, d) })
}

// ── Pages ────────────────────────────────────────────────────────────────────

/** One story page: its own gradient, and whether its text is dark (on light gradients). */
private data class Page(val key: String, val colors: List<Color>, val content: @Composable (YearInReview) -> Unit)

private val INK = Color(0xFFFFFFFF)
private val INK_SOFT = Color(0xE6FFFFFF)

private fun pages(r: YearInReview, onPeriod: (ReviewPeriod, String?) -> Unit): List<Page> = buildList {
    add(Page("intro", listOf(Color(0xFF3B1F6B), Color(0xFFB0306A), Color(0xFFE8663D))) { IntroPage(it, onPeriod) })
    if (r.empty) return@buildList
    add(Page("spent", listOf(Color(0xFF14213D), Color(0xFF6A2C70))) { SpentPage(it) })
    if (r.topCategories.isNotEmpty()) add(Page("categories", listOf(Color(0xFF0B3D3A), Color(0xFF12685E))) { CategoriesPage(it) })
    if (r.topMerchants.isNotEmpty()) add(Page("merchants", listOf(Color(0xFF3A0CA3), Color(0xFF7209B7))) { MerchantsPage(it) })
    r.mostVisited?.takeIf { it.visits >= 3 }?.let { add(Page("visited", listOf(Color(0xFF7A1F2B), Color(0xFFB23A48))) { VisitedPage(r) }) }
    r.biggestPurchase?.let { add(Page("biggest", listOf(Color(0xFF1B263B), Color(0xFF415A77))) { BiggestPage(r) }) }
    if (r.biggestBucket != null) add(Page("buckets", listOf(Color(0xFF123C69), Color(0xFF1B6F8A))) { BucketsPage(r) })
    add(Page("nospend", listOf(Color(0xFF2D3A1F), Color(0xFF52733A))) { NoSpendPage(r) })
    add(Page("saved", listOf(Color(0xFF4A1942), Color(0xFF893168))) { SavedPage(r) })
    add(Page("summary", listOf(Color(0xFF1E1E24), Color(0xFF3A3A48))) { SummaryPage(r) })
}

@Composable
fun YearInReviewScreen(state: YearInReviewUiState, onClose: () -> Unit = {}, onPeriod: (ReviewPeriod, String?) -> Unit = { _, _ -> }, initialPage: Int = 0) {
    when (val d = state.data) {
        Loadable.Loading -> Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF3B1F6B), Color(0xFFB0306A)))), contentAlignment = Alignment.Center) {
            Text("Wrapping up your ${state.period.label.lowercase()}…", color = INK, style = MaterialTheme.typography.titleMedium)
        }
        is Loadable.Failed -> Box(Modifier.fillMaxSize().background(Color(0xFF1E1E24)).padding(32.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Couldn't load your ${state.period.label.lowercase()}", color = INK, style = MaterialTheme.typography.titleLarge)
                Text(d.message, color = INK_SOFT, style = MaterialTheme.typography.bodyMedium)
                androidx.compose.material3.TextButton(onClick = onClose) { Text("Close", color = INK) }
            }
        }
        is Loadable.Ready -> key(d.value.start, d.value.period) { Story(d.value, onClose, onPeriod, initialPage) }
    }
}

@Composable
private fun Story(r: YearInReview, onClose: () -> Unit, onPeriod: (ReviewPeriod, String?) -> Unit, initialPage: Int) {
    val list = pages(r, onPeriod)
    val pager = rememberPagerState(initialPage = initialPage.coerceIn(0, list.lastIndex)) { list.size }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val layer = rememberGraphicsLayer()
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(pager, Modifier.fillMaxSize(), key = { list[it].key }) { i ->
            val page = list[i]
            Box(
                Modifier.fillMaxSize()
                    // The visible page is recorded so "Share" can turn it into an image.
                    .then(if (i == pager.currentPage) Modifier.drawWithContent { layer.record { this@drawWithContent.drawContent() }; drawLayer(layer) } else Modifier)
                    .background(Brush.linearGradient(page.colors))
                    .pointerInput(pager) {
                        detectTapGestures { offset ->
                            val next = if (offset.x > size.width / 3) pager.currentPage + 1 else pager.currentPage - 1
                            scope.launch { pager.animateScrollToPage(next.coerceIn(0, list.lastIndex)) }
                        }
                    }
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(start = 28.dp, end = 28.dp, top = 72.dp, bottom = 40.dp),
            ) { page.content(r) }
        }
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Progress(pager, list.size)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Centsible · ${r.label}", color = INK_SOFT, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f).padding(start = 4.dp))
                IconButton(onClick = { scope.launch { share(context, layer, r.label) } }) { Icon(Icons.Rounded.Share, contentDescription = "Share this page", tint = INK) }
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = "Close", tint = INK) }
            }
        }
    }
}

@Composable
private fun Progress(pager: PagerState, count: Int) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp).clearAndSetSemantics { contentDescription = "Page ${pager.currentPage + 1} of $count" },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        repeat(count) { i ->
            Box(Modifier.weight(1f).height(3.dp).background(if (i <= pager.currentPage) INK else Color(0x55FFFFFF), RoundedCornerShape(2.dp)))
        }
    }
}

/** Saves the recorded page as a PNG in the cache and opens the share sheet. */
private suspend fun share(context: Context, layer: GraphicsLayer, label: String) {
    val bitmap = layer.toImageBitmap().asAndroidBitmap()
    val file = withContext(Dispatchers.IO) {
        File(context.cacheDir, "shared").apply { mkdirs() }.resolve("centsible-${label.replace(Regex("[^A-Za-z0-9]+"), "-")}.png").also { f ->
            f.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.share", file)
    val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "Share your $label"))
}

// ── Page content ─────────────────────────────────────────────────────────────

@Composable
private fun Kicker(text: String) = Text(text.uppercase(), color = INK_SOFT, style = MaterialTheme.typography.labelLarge, letterSpacing = 2.sp)

@Composable
private fun Huge(text: String, size: Int = 56) = Text(
    text, color = INK, fontSize = size.sp, lineHeight = (size * 1.05).sp, fontWeight = FontWeight.Black,
    modifier = Modifier.semantics { heading() },
)

@Composable
private fun Body(text: String, modifier: Modifier = Modifier) = Text(text, color = INK_SOFT, style = MaterialTheme.typography.titleMedium, modifier = modifier)

private fun money(m: Money) = MoneyFormat.format(m).substringBeforeLast('.')
private val DAY = DateTimeFormatter.ofPattern("MMMM d")

@Composable
private fun IntroPage(r: YearInReview, onPeriod: (ReviewPeriod, String?) -> Unit) {
    val noun = r.period.label.lowercase()
    Column(Modifier.fillMaxSize()) {
        // Week, month, quarter or year.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReviewPeriod.entries.forEach { p ->
                val selected = p == r.period
                Text(
                    p.label,
                    color = if (selected) Color(0xFF3B1F6B) else INK,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .background(if (selected) INK else Color(0x33FFFFFF), RoundedCornerShape(50))
                        .clickable(role = androidx.compose.ui.semantics.Role.Tab) { if (!selected) onPeriod(p, null) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Kicker(if (r.complete) "Your $noun in money" else "Your $noun so far")
        Huge(r.label, if (r.period == ReviewPeriod.Year) 120 else 52)
        Body(if (r.empty) "There's nothing to look back on for ${r.label} yet." else "Where it went, where you went, and what you kept. Tap to begin →")
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            r.previousStart?.let { prev ->
                androidx.compose.material3.OutlinedButton(onClick = { onPeriod(r.period, prev) }) { Text("‹ Previous $noun", color = INK) }
            }
            Spacer(Modifier.width(8.dp))
            r.nextStart?.let { next ->
                androidx.compose.material3.OutlinedButton(onClick = { onPeriod(r.period, next) }) { Text("Next $noun ›", color = INK) }
            }
        }
    }
}

@Composable
private fun SpentPage(r: YearInReview) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Kicker("You spent")
        Huge(money(r.spending))
        Spacer(Modifier.height(16.dp))
        Body("across ${"%,d".format(r.purchases)} purchases, about ${money(r.dailyAverage)} a day.")
        val prev = r.previousPeriod
        prev?.spendingChangePct?.let { pct ->
            Spacer(Modifier.height(24.dp))
            Text(
                when {
                    pct < 0 -> "That's ${-pct}% less than ${prev.label}${if (r.complete) "" else " by this point"}. 🎉"
                    pct > 0 -> "That's $pct% more than ${prev.label}${if (r.complete) "" else " by this point"}."
                    else -> "Exactly the same as ${prev.label}. Steady."
                },
                color = INK, style = MaterialTheme.typography.headlineSmall,
            )
        }
    }
}

@Composable
private fun CategoriesPage(r: YearInReview) {
    val top = r.topCategories.first()
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Kicker("Your #1 category")
        Huge(top.name, 48)
        Body("${money(top.amount)}, ${(top.share * 100).roundToInt()}% of everything you spent.")
        Spacer(Modifier.height(32.dp))
        val max = r.topCategories.maxOf { it.amount.minor }.coerceAtLeast(1)
        r.topCategories.forEachIndexed { i, c ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", color = INK, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.width(28.dp))
                Column(Modifier.weight(1f)) {
                    Row {
                        Text(c.name, color = INK, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(money(c.amount), color = INK_SOFT, style = MaterialTheme.typography.titleMedium)
                    }
                    Box(Modifier.fillMaxWidth().height(6.dp).padding(top = 2.dp).background(Color(0x33FFFFFF), RoundedCornerShape(3.dp))) {
                        Box(Modifier.fillMaxWidth(c.amount.minor.toFloat() / max).fillMaxHeight().background(INK, RoundedCornerShape(3.dp)))
                    }
                }
            }
        }
    }
}

@Composable
private fun MerchantsPage(r: YearInReview) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Kicker("Your top merchants")
        Spacer(Modifier.height(16.dp))
        r.topMerchants.forEachIndexed { i, m ->
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", color = INK, fontSize = if (i == 0) 44.sp else 30.sp, fontWeight = FontWeight.Black, modifier = Modifier.width(56.dp))
                Column(Modifier.weight(1f)) {
                    Text(m.name, color = INK, fontSize = if (i == 0) 26.sp else 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${money(m.amount)} · ${m.visits} visit${if (m.visits == 1) "" else "s"}", color = INK_SOFT, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun VisitedPage(r: YearInReview) {
    val m = r.mostVisited ?: return
    val days = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(r.start), minOf(LocalDate.parse(r.end), LocalDate.now())).toInt() + 1
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Kicker("Your regular spot")
        Body("You went to")
        Huge(m.name, 48)
        Huge("${m.visits} times", 40)
        Spacer(Modifier.height(16.dp))
        Body("That's about once every ${(days / m.visits.toFloat()).roundToInt().coerceAtLeast(1)} days, ${money(m.amount)} in all.")
    }
}

@Composable
private fun BiggestPage(r: YearInReview) {
    val b = r.biggestPurchase ?: return
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Kicker("Your biggest purchase")
        Huge(money(b.amount))
        Spacer(Modifier.height(12.dp))
        Text(b.payeeName ?: "A single purchase", color = INK, style = MaterialTheme.typography.headlineSmall)
        Body(listOfNotNull(LocalDate.parse(b.date).format(DAY), b.categoryName).joinToString(" · "))
    }
}

/** The period over time: months of a year, weeks of a quarter, days of a month or week. */
private fun bucketName(r: YearInReview, b: YearInReview.Bucket): String = when (r.period) {
    ReviewPeriod.Year -> java.time.Month.of(b.key.takeLast(2).toInt()).getDisplayName(TextStyle.FULL, Locale.getDefault())
    ReviewPeriod.Quarter -> "The week of ${LocalDate.parse(b.start).format(DAY)}"
    ReviewPeriod.Month -> LocalDate.parse(b.start).format(DAY)
    ReviewPeriod.Week -> LocalDate.parse(b.start).dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
}

@Composable
private fun BucketsPage(r: YearInReview) {
    val big = r.biggestBucket ?: return
    val small = r.smallestBucket
    val today = LocalDate.now().toString()
    val max = r.buckets.maxOfOrNull { it.spending.minor }?.coerceAtLeast(1) ?: 1
    val (kicker, unit) = when (r.period) {
        ReviewPeriod.Year -> "Month by month" to "month"
        ReviewPeriod.Quarter -> "Week by week" to "week"
        else -> "Day by day" to "day"
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Kicker(kicker)
        Huge(bucketName(r, big), if (r.period == ReviewPeriod.Quarter) 36 else 48)
        Body("was your biggest $unit: ${money(big.spending)}.")
        Spacer(Modifier.height(28.dp))
        Row(
            Modifier.fillMaxWidth().height(160.dp).clearAndSetSemantics {
                contentDescription = r.buckets.filter { it.start <= today }.joinToString { "${bucketName(r, it)} ${money(it.spending)}" }
            },
            horizontalArrangement = Arrangement.spacedBy(if (r.buckets.size > 14) 2.dp else 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            r.buckets.forEachIndexed { i, b ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    val h = (b.spending.minor.coerceAtLeast(0).toFloat() / max).coerceIn(0.02f, 1f)
                    Box(Modifier.fillMaxWidth().height((130 * h).dp).background(if (b.key == big.key) INK else Color(0x66FFFFFF), RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)))
                    // A month's 30 days only label every fifth.
                    val label = when (r.period) {
                        ReviewPeriod.Year -> b.label.take(1)
                        ReviewPeriod.Month -> if (i % 5 == 0) b.label else ""
                        ReviewPeriod.Quarter -> if (i % 3 == 0) b.label.substringBefore(' ') else ""
                        ReviewPeriod.Week -> b.label.take(2)
                    }
                    Text(label, color = INK_SOFT, style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false, modifier = Modifier.wrapContentWidth(unbounded = true))
                }
            }
        }
        if (small != null && small != big) {
            Spacer(Modifier.height(20.dp))
            Body("Most frugal: ${bucketName(r, small)}, at ${money(small.spending)}.")
        }
    }
}

@Composable
private fun NoSpendPage(r: YearInReview) {
    val s = r.longestNoSpendStreak
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Kicker("No-spend days")
        Huge("${r.noSpendDays}", 96)
        Body("days you didn't buy a thing.")
        if (s.days >= 2 && s.start != null && s.end != null) {
            Spacer(Modifier.height(24.dp))
            Text("Longest streak: ${s.days} days", color = INK, style = MaterialTheme.typography.headlineSmall)
            Body("${LocalDate.parse(s.start).format(DAY)} to ${LocalDate.parse(s.end).format(DAY)}")
        }
    }
}

@Composable
private fun SavedPage(r: YearInReview) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Kicker("You brought in")
        Huge(money(r.income), 48)
        Spacer(Modifier.height(16.dp))
        if (r.saved.isNegative) {
            Body("and spent ${money(r.saved.abs())} more than that. Next ${r.period.label.lowercase()}'s a fresh start.")
        } else {
            Body("and kept")
            Huge(money(r.saved), 48)
            r.savingsRate?.let { Body("That's $it% of what came in. 💪") }
        }
        if (r.newMerchants > 0) {
            Spacer(Modifier.height(28.dp))
            Text("You tried ${r.newMerchants} new place${if (r.newMerchants == 1) "" else "s"} too.", color = INK, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun SummaryPage(r: YearInReview) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Kicker("${r.label} wrapped")
        Spacer(Modifier.height(20.dp))
        SummaryRow("Spent", money(r.spending))
        SummaryRow("Brought in", money(r.income))
        SummaryRow(if (r.saved.isNegative) "Overspent" else "Kept", money(r.saved.abs()))
        r.topCategories.firstOrNull()?.let { SummaryRow("Top category", it.name) }
        r.topMerchants.firstOrNull()?.let { SummaryRow("Top merchant", it.name) }
        SummaryRow("No-spend days", "${r.noSpendDays}")
        Spacer(Modifier.height(28.dp))
        Body("Tap share to send this card.")
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = INK_SOFT, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Text(value, color = INK, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
