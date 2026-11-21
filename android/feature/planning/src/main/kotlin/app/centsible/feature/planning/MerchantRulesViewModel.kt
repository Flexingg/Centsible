package app.centsible.feature.planning

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.centsible.core.designsystem.component.Loadable
import app.centsible.core.domain.BudgetChanges
import app.centsible.core.domain.BudgetEngine
import app.centsible.core.domain.PlanningGateway
import app.centsible.core.domain.RuleTools
import app.centsible.core.domain.SelectedBudget
import app.centsible.core.domain.SessionStore
import app.centsible.core.domain.userMessage
import app.centsible.core.model.CategoryId
import app.centsible.core.model.Feature
import app.centsible.core.model.Payee
import app.centsible.core.model.PayeeStat
import app.centsible.core.model.RulePreview
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** State for the current merchant card at the front of the queue. */
data class MerchantRuleCardState(
    val merchant: PayeeStat,
    val mode: MatchMode,
    val keyword: String,
    val renameTo: String,
    val excludeKeywords: List<String> = emptyList(),
    val notesContains: String = "",
    val showNotesFilter: Boolean = false,
    val categoryId: CategoryId? = null,
    val suggestedCategoryId: CategoryId? = null,
    val applyToExisting: Boolean = true,
    val preview: RulePreview? = null,
    val previewing: Boolean = false,
    val previewError: String? = null,
    val previewExpanded: Boolean = false,
) {
    val canSave: Boolean
        get() = categoryId != null && (mode == MatchMode.Exact || keyword.trim().isNotEmpty())
}

data class MerchantRulesData(
    val queue: List<PayeeStat>,
    val categoryNames: Map<String, String>,
    val accountNames: Map<String, String>,
    val payees: List<Payee>,
    val totalInitial: Int,
    val doneCount: Int = 0,
    val skippedCount: Int = 0,
    val transactionsUpdated: Int = 0,
)

data class MerchantRulesUiState(
    val data: Loadable<MerchantRulesData> = Loadable.Loading,
    val card: MerchantRuleCardState? = null,
    val canEdit: Boolean = false,
    val pickingCategory: Boolean = false,
    val saving: Boolean = false,
    val message: String? = null,
) {
    val remaining: Int get() = data.valueOrNull?.queue?.size ?: 0
}

@HiltViewModel
class MerchantRulesViewModel @Inject constructor(
    private val planning: PlanningGateway,
    private val tools: RuleTools,
    private val engine: BudgetEngine,
    private val selectedBudget: SelectedBudget,
    private val sessions: SessionStore,
    changes: BudgetChanges,
) : ViewModel() {
    private val state = MutableStateFlow(MerchantRulesUiState())
    val uiState: StateFlow<MerchantRulesUiState> = state.asStateFlow()

    private var previewJob: Job? = null
    private var leftForEditor = false

    init {
        load()
        // If a category is created from the picker sheet, keep category names up to date.
        viewModelScope.launch { changes.changes.collect { refreshCategories() } }
    }

    fun load() = viewModelScope.launch {
        state.update { it.copy(data = Loadable.Loading) }
        runCatching { fetchQueue() }
            .onSuccess { (data, canEdit) ->
                val card = data.queue.firstOrNull()?.let(::cardFor)
                state.update { it.copy(data = Loadable.Ready(data), card = card, canEdit = canEdit) }
                if (card != null) schedulePreview(0)
            }
            .onFailure { e -> state.update { it.copy(data = Loadable.Failed(e.userMessage())) } }
    }

    fun leavingForEditor() {
        leftForEditor = true
    }

    /** Back from the full rule editor or transaction editor: reload the remaining unruled merchants. */
    fun resumed() {
        if (!leftForEditor) return
        leftForEditor = false
        val before = state.value.data.valueOrNull ?: return
        viewModelScope.launch {
            runCatching { fetchQueue() }.onSuccess { (fresh, canEdit) ->
                val remaining = fresh.queue
                val cleared = (before.queue.size - remaining.size).coerceAtLeast(0)
                val merged = fresh.copy(
                    totalInitial = before.totalInitial.coerceAtLeast(remaining.size + before.doneCount + cleared),
                    doneCount = before.doneCount + cleared,
                    skippedCount = before.skippedCount,
                    transactionsUpdated = before.transactionsUpdated,
                )
                val card = remaining.firstOrNull()?.let(::cardFor)
                state.update { it.copy(data = Loadable.Ready(merged), card = card, canEdit = canEdit) }
                if (card != null) schedulePreview(0)
            }
        }
    }

    private suspend fun fetchQueue(): Pair<MerchantRulesData, Boolean> = coroutineScope {
        val b = selectedBudget()
        val stats = async { planning.payeeStats(b) }
        val rules = async { planning.rules(b) }
        val groups = async { engine.categoryGroups(b) }
        val accounts = async { engine.accounts(b) }
        val payees = async { engine.payees(b) }
        val caps = async { engine.capabilities() }
        val r = rules.await()
        val queue = stats.await()
            .filter { it.transferAccountId == null && it.transactionCount > 0 && !MerchantRuleHelper.isCoveredByRules(it, r) }
            .sortedWith(compareByDescending<PayeeStat> { it.transactionCount }.thenBy { it.name.lowercase() })
        val catNames = groups.await().flatMap { it.categories }.associate { it.id.raw to it.name }
        val acctNames = accounts.await().associate { it.id.raw to it.name }
        val canEdit = sessions.current()?.member?.role?.canWrite == true && caps.await().has(Feature.RulesWrite)
        MerchantRulesData(
            queue = queue,
            categoryNames = catNames,
            accountNames = acctNames,
            payees = payees.await(),
            totalInitial = queue.size,
        ) to canEdit
    }

    private suspend fun refreshCategories() {
        if (state.value.data.valueOrNull == null) return
        runCatching { engine.categoryGroups(selectedBudget()) }.onSuccess { g ->
            val catNames = g.flatMap { it.categories }.associate { it.id.raw to it.name }
            state.update { s ->
                val cur = s.data.valueOrNull ?: return@update s
                s.copy(data = Loadable.Ready(cur.copy(categoryNames = catNames)))
            }
        }
    }

    private fun cardFor(merchant: PayeeStat): MerchantRuleCardState {
        val suggestion = MerchantRuleHelper.suggest(merchant.name)
        return MerchantRuleCardState(
            merchant = merchant,
            mode = suggestion.mode,
            keyword = suggestion.keyword,
            renameTo = suggestion.renameTo,
        )
    }

    fun setMode(mode: MatchMode) {
        state.update { s ->
            val c = s.card ?: return@update s
            s.copy(card = c.copy(mode = mode, previewError = null))
        }
        schedulePreview(150)
    }

    fun setKeyword(keyword: String) {
        state.update { s ->
            val c = s.card ?: return@update s
            s.copy(card = c.copy(keyword = keyword, previewError = null))
        }
        schedulePreview(400)
    }

    fun setRenameTo(renameTo: String) {
        state.update { s ->
            val c = s.card ?: return@update s
            s.copy(card = c.copy(renameTo = renameTo))
        }
    }

    fun togglePreviewExpanded() {
        state.update { s ->
            val c = s.card ?: return@update s
            s.copy(card = c.copy(previewExpanded = !c.previewExpanded))
        }
    }

    /** Excludes a mismatched merchant from the current keyword rule using its distinguishing term. */
    fun excludeMerchant(candidatePayeeName: String) {
        val card = state.value.card ?: return
        val term = MerchantRuleHelper.extractExclusionTerm(card.keyword, candidatePayeeName)
        if (term.isEmpty()) return
        addExcludeKeyword(term)
    }

    fun addExcludeKeyword(rawTerm: String) {
        val term = rawTerm.trim()
        if (term.isEmpty()) return
        state.update { s ->
            val c = s.card ?: return@update s
            if (c.excludeKeywords.any { it.equals(term, ignoreCase = true) }) return@update s
            s.copy(
                card = c.copy(
                    mode = MatchMode.Keyword,
                    excludeKeywords = c.excludeKeywords + term,
                    previewError = null,
                ),
                message = "Excluded \"$term\" from this rule",
            )
        }
        schedulePreview(150)
    }

    fun removeExcludeKeyword(term: String) {
        state.update { s ->
            val c = s.card ?: return@update s
            s.copy(
                card = c.copy(
                    excludeKeywords = c.excludeKeywords.filterNot { it.equals(term, ignoreCase = true) },
                    previewError = null,
                ),
            )
        }
        schedulePreview(150)
    }

    /** Narrows the keyword to match a specific merchant from the expanded preview list. */
    fun narrowKeywordTo(candidatePayeeName: String) {
        val suggestion = MerchantRuleHelper.suggest(candidatePayeeName)
        val kw = suggestion.keyword.trim().ifEmpty { candidatePayeeName.trim() }
        if (kw.isEmpty()) return
        state.update { s ->
            val c = s.card ?: return@update s
            s.copy(
                card = c.copy(
                    mode = MatchMode.Keyword,
                    keyword = kw,
                    renameTo = suggestion.renameTo.ifEmpty { c.renameTo },
                    excludeKeywords = emptyList(),
                    previewError = null,
                ),
                message = "Narrowed keyword to \"$kw\"",
            )
        }
        schedulePreview(150)
    }

    fun setShowNotesFilter(show: Boolean) {
        state.update { s ->
            val c = s.card ?: return@update s
            s.copy(
                card = c.copy(
                    showNotesFilter = show,
                    notesContains = if (show) c.notesContains else "",
                    previewError = null,
                ),
            )
        }
        if (!show) schedulePreview(150)
    }

    fun setNotesContains(text: String) {
        state.update { s ->
            val c = s.card ?: return@update s
            s.copy(card = c.copy(notesContains = text, previewError = null))
        }
        schedulePreview(400)
    }

    fun openCategoryPicker(open: Boolean) {
        state.update { it.copy(pickingCategory = open) }
    }

    fun setCategory(categoryId: CategoryId?) {
        state.update { s ->
            val c = s.card ?: return@update s
            s.copy(card = c.copy(categoryId = categoryId), pickingCategory = false)
        }
    }

    fun setApplyToExisting(apply: Boolean) {
        state.update { s ->
            val c = s.card ?: return@update s
            s.copy(card = c.copy(applyToExisting = apply))
        }
    }

    fun skip() {
        previewJob?.cancel()
        val d = state.value.data.valueOrNull ?: return
        val current = state.value.card?.merchant ?: return
        val nextQueue = d.queue.filterNot { it.id == current.id }
        val nextCard = nextQueue.firstOrNull()?.let(::cardFor)
        state.update {
            it.copy(
                data = Loadable.Ready(d.copy(queue = nextQueue, skippedCount = d.skippedCount + 1)),
                card = nextCard,
            )
        }
        if (nextCard != null) schedulePreview(0)
    }

    fun saveAndNext() {
        val d = state.value.data.valueOrNull ?: return
        val card = state.value.card ?: return
        val categoryId = card.categoryId ?: return
        if (!card.canSave || state.value.saving) return

        previewJob?.cancel()
        state.update { it.copy(saving = true) }

        viewModelScope.launch {
            runCatching {
                val b = selectedBudget()
                val cleanName = card.renameTo.trim()
                val wantsRename = cleanName.isNotEmpty() && cleanName != card.merchant.name
                val isSplitByNotes = card.notesContains.trim().isNotEmpty()
                var updatedPayees = d.payees

                val targetPayeeId = when {
                    !wantsRename -> null
                    // When splitting a single merchant by notes, keep the original payee intact
                    // and set payee via the rule action only on the matching split.
                    card.mode == MatchMode.Exact && !isSplitByNotes -> {
                        planning.renamePayee(b, card.merchant.id, cleanName)
                        updatedPayees = updatedPayees.map { p -> if (p.id == card.merchant.id) p.copy(name = cleanName) else p }
                        null
                    }
                    else -> {
                        val existing = updatedPayees.firstOrNull {
                            it.transferAccountId == null && it.name.equals(cleanName, ignoreCase = true)
                        }
                        if (existing != null) {
                            existing.id
                        } else if (!isSplitByNotes) {
                            planning.renamePayee(b, card.merchant.id, cleanName)
                            updatedPayees = updatedPayees.map { p -> if (p.id == card.merchant.id) p.copy(name = cleanName) else p }
                            card.merchant.id
                        } else {
                            null
                        }
                    }
                }

                val draft = MerchantRuleHelper.buildDraft(
                    mode = card.mode,
                    payeeId = card.merchant.id,
                    keyword = card.keyword,
                    categoryId = categoryId,
                    targetPayeeId = targetPayeeId,
                    excludeKeywords = card.excludeKeywords,
                    notesContains = card.notesContains,
                )
                val created = planning.createRule(b, draft)
                val updated = if (card.applyToExisting) tools.run(b, created.id) else 0
                Triple(updated, updatedPayees, cleanName.takeIf { wantsRename } ?: card.merchant.name)
            }
                .onSuccess { (updated, updatedPayees, displayName) ->
                    val isSplitByNotes = card.notesContains.trim().isNotEmpty()
                    if (isSplitByNotes) {
                        // Stay on this merchant so the user can add another split rule (or Skip when done).
                        val resetCard = card.copy(
                            notesContains = "",
                            categoryId = null,
                            renameTo = "",
                            previewExpanded = true,
                        )
                        val msg = buildString {
                            append("Split rule saved")
                            if (updated > 0) append(" · updated $updated transaction${if (updated == 1) "" else "s"}")
                            append(". Add another split or tap Skip.")
                        }
                        state.update {
                            it.copy(
                                data = Loadable.Ready(
                                    d.copy(
                                        payees = updatedPayees,
                                        transactionsUpdated = d.transactionsUpdated + updated,
                                    ),
                                ),
                                card = resetCard,
                                saving = false,
                                message = msg,
                            )
                        }
                        schedulePreview(0)
                    } else {
                        val nextQueue = d.queue.filterNot { m ->
                            m.id == card.merchant.id || (card.mode == MatchMode.Keyword && MerchantRuleHelper.matchesKeyword(m.name, card.keyword, card.excludeKeywords))
                        }
                        val sweptCount = (d.queue.size - nextQueue.size).coerceAtLeast(1)
                        val nextCard = nextQueue.firstOrNull()?.let(::cardFor)
                        val msg = buildString {
                            append("Rule saved for $displayName")
                            if (updated > 0) append(" · updated $updated transaction${if (updated == 1) "" else "s"}")
                            if (sweptCount > 1) append(" ($sweptCount merchants)")
                        }
                        state.update {
                            it.copy(
                                data = Loadable.Ready(
                                    d.copy(
                                        queue = nextQueue,
                                        payees = updatedPayees,
                                        doneCount = d.doneCount + sweptCount,
                                        transactionsUpdated = d.transactionsUpdated + updated,
                                    ),
                                ),
                                card = nextCard,
                                saving = false,
                                message = msg,
                            )
                        }
                        if (nextCard != null) schedulePreview(0)
                    }
                }
                .onFailure { e ->
                    state.update { it.copy(saving = false, message = e.userMessage()) }
                }
        }
    }

    fun messageShown() = state.update { it.copy(message = null) }

    private fun schedulePreview(wait: Long) {
        previewJob?.cancel()
        val card = state.value.card ?: return
        if (card.mode == MatchMode.Keyword && card.keyword.trim().isEmpty()) {
            state.update { s ->
                val c = s.card ?: return@update s
                s.copy(card = c.copy(preview = null, previewing = false, previewError = null))
            }
            return
        }
        val merchantId = card.merchant.id
        state.update { s ->
            val c = s.card?.takeIf { it.merchant.id == merchantId } ?: return@update s
            s.copy(card = c.copy(previewing = true, previewError = null))
        }
        previewJob = viewModelScope.launch {
            if (wait > 0) delay(wait)
            val current = state.value.card?.takeIf { it.merchant.id == merchantId } ?: return@launch
            val draft = MerchantRuleHelper.buildDraft(
                mode = current.mode,
                payeeId = current.merchant.id,
                keyword = current.keyword,
                categoryId = null,
                excludeKeywords = current.excludeKeywords,
                notesContains = current.notesContains,
            )
            runCatching { tools.preview(selectedBudget(), draft, limit = PREVIEW_LIMIT) }
                .onSuccess { preview ->
                    val suggested = preview.items
                        .mapNotNull { it.transaction.categoryId }
                        .groupingBy { it }
                        .eachCount()
                        .maxByOrNull { it.value }
                        ?.key
                    state.update { s ->
                        val c = s.card?.takeIf { it.merchant.id == merchantId } ?: return@update s
                        s.copy(
                            card = c.copy(
                                preview = preview,
                                previewing = false,
                                previewError = null,
                                suggestedCategoryId = suggested,
                            ),
                        )
                    }
                }
                .onFailure { e ->
                    state.update { s ->
                        val c = s.card?.takeIf { it.merchant.id == merchantId } ?: return@update s
                        s.copy(card = c.copy(previewing = false, previewError = e.userMessage()))
                    }
                }
        }
    }

    private companion object {
        const val PREVIEW_LIMIT = 100
    }
}
