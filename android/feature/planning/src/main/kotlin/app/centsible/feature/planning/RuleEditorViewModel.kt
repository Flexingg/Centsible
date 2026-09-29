package app.centsible.feature.planning

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.centsible.core.designsystem.component.Loadable
import app.centsible.core.domain.BudgetEngine
import app.centsible.core.domain.PlanningGateway
import app.centsible.core.domain.RuleTools
import app.centsible.core.domain.SelectedBudget
import app.centsible.core.domain.SessionStore
import app.centsible.core.domain.userMessage
import app.centsible.core.model.Account
import app.centsible.core.model.CategoryGroup
import app.centsible.core.model.Feature
import app.centsible.core.model.Payee
import app.centsible.core.model.RulePreview
import app.centsible.core.model.RuleValue
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the editor needs to show names and offer choices. */
data class RuleEditorData(
    val payees: List<Payee>,
    val groups: List<CategoryGroup>,
    val accounts: List<Account>,
    val names: Describe.Names,
    /** Set when editing the hidden rule behind a schedule (read-only). */
    val scheduleRule: Boolean,
)

data class RuleEditorUiState(
    val id: String? = null,
    val data: Loadable<RuleEditorData> = Loadable.Loading,
    val form: RuleFormState = RuleFormState(),
    val canEdit: Boolean = false,
    val preview: RulePreview? = null,
    val previewing: Boolean = false,
    val previewError: String? = null,
    val saving: Boolean = false,
    /** After saving: how many existing transactions it matches, to offer running it on them. */
    val offerRun: Int? = null,
    val confirmDelete: Boolean = false,
    val message: String? = null,
    val done: Boolean = false,
) {
    val problem get() = RuleFormCodec.problem(form)
}

@HiltViewModel
class RuleEditorViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val planning: PlanningGateway,
    private val tools: RuleTools,
    private val engine: BudgetEngine,
    private val selectedBudget: SelectedBudget,
    private val sessions: SessionStore,
    changes: app.centsible.core.domain.BudgetChanges,
) : ViewModel() {
    private val ruleId = saved.get<String>(ARG_ID)?.takeIf { it.isNotBlank() }
    private val prefillPayee = saved.get<String>(ARG_PAYEE)?.takeIf { it.isNotBlank() }
    private val prefillCategory = saved.get<String>(ARG_CATEGORY)?.takeIf { it.isNotBlank() }
    private val state = MutableStateFlow(RuleEditorUiState(id = ruleId))
    val uiState: StateFlow<RuleEditorUiState> = state.asStateFlow()
    private var previewJob: Job? = null
    private var savedId: String? = ruleId

    init {
        load()
        // A category made from the picker: refresh names and choices, keep the form.
        viewModelScope.launch { changes.changes.collect { refreshCategories() } }
    }

    private suspend fun refreshCategories() {
        val d = state.value.data.valueOrNull ?: return
        runCatching { engine.categoryGroups(selectedBudget()) }.onSuccess { g ->
            val names = d.names.copy(categories = g.flatMap { it.categories }.associate { it.id.raw to it.name })
            state.update { it.copy(data = Loadable.Ready(d.copy(groups = g, names = names))) }
        }
    }

    fun load() = viewModelScope.launch {
        runCatching {
            val b = selectedBudget()
            val rules = async { planning.rules(b) }
            val payees = async { engine.payees(b) }
            val groups = async { engine.categoryGroups(b) }
            val accounts = async { engine.accounts(b) }
            val caps = async { engine.capabilities() }
            val p = payees.await()
            val g = groups.await()
            val a = accounts.await()
            val rule = ruleId?.let { id -> rules.await().firstOrNull { it.id == id } ?: error("That rule no longer exists") }
            val names = Describe.Names(p.associate { it.id.raw to it.name }, g.flatMap { it.categories }.associate { it.id.raw to it.name }, a.associate { it.id.raw to it.name })
            val form = rule?.let(RuleFormCodec::from) ?: RuleFormState(
                conditions = listOf(CondRow("payee", "is", prefillPayee?.let(RuleValue::Text) ?: RuleValue.Null)),
                actions = listOf(ActRow("set", "category", prefillCategory?.let(RuleValue::Text) ?: RuleValue.Null)),
            )
            val canEdit = sessions.current()?.member?.role?.canWrite == true && caps.await().has(Feature.RulesWrite) && rule?.scheduleId == null
            Triple(RuleEditorData(p, g, a, names, rule?.scheduleId != null), form, canEdit)
        }
            .onSuccess { (d, form, canEdit) ->
                state.update { it.copy(data = Loadable.Ready(d), form = form, canEdit = canEdit) }
                schedulePreview(0)
            }
            .onFailure { e -> state.update { it.copy(data = Loadable.Failed(e.userMessage())) } }
    }

    fun edit(f: (RuleFormState) -> RuleFormState) {
        state.update { it.copy(form = f(it.form)) }
        schedulePreview()
    }

    /** Works out matches and changes shortly after the last edit (once the rule is complete). */
    private fun schedulePreview(wait: Long = 600) {
        previewJob?.cancel()
        if (state.value.problem != null) {
            state.update { it.copy(previewing = false) }
            return
        }
        state.update { it.copy(previewing = true) }
        previewJob = viewModelScope.launch {
            delay(wait)
            runCatching { tools.preview(selectedBudget(), RuleFormCodec.toDraft(state.value.form), limit = 15) }
                .onSuccess { p -> state.update { it.copy(preview = p, previewing = false, previewError = null) } }
                .onFailure { e -> state.update { it.copy(previewing = false, previewError = e.userMessage()) } }
        }
    }

    fun save() {
        val draft = RuleFormCodec.toDraft(state.value.form)
        state.update { it.copy(saving = true) }
        viewModelScope.launch {
            runCatching {
                val b = selectedBudget()
                val rule = savedId?.let { planning.updateRule(b, it, draft) } ?: planning.createRule(b, draft)
                savedId = rule.id
                tools.preview(b, draft, limit = 1).matchCount
            }
                .onSuccess { matches -> state.update { it.copy(saving = false, offerRun = matches.takeIf { n -> n > 0 }, done = matches == 0, message = "Rule saved") } }
                .onFailure { e -> state.update { it.copy(saving = false, message = e.userMessage()) } }
        }
    }

    /** Runs the saved rule on the existing transactions it matches. */
    fun runOnExisting(run: Boolean) {
        val id = savedId
        state.update { it.copy(offerRun = null) }
        if (!run || id == null) {
            state.update { it.copy(done = true) }
            return
        }
        viewModelScope.launch {
            runCatching { tools.run(selectedBudget(), id) }
                .onSuccess { n -> state.update { it.copy(done = true, message = "Updated $n transaction${if (n == 1) "" else "s"}") } }
                .onFailure { e -> state.update { it.copy(done = true, message = e.userMessage()) } }
        }
    }

    fun askDelete(ask: Boolean) = state.update { it.copy(confirmDelete = ask) }

    fun delete() {
        val id = savedId ?: return
        state.update { it.copy(confirmDelete = false, saving = true) }
        viewModelScope.launch {
            runCatching { planning.deleteRule(selectedBudget(), id) }
                .onSuccess { state.update { it.copy(saving = false, done = true, message = "Rule deleted") } }
                .onFailure { e -> state.update { it.copy(saving = false, message = e.userMessage()) } }
        }
    }

    fun messageShown() = state.update { it.copy(message = null) }

    companion object {
        const val ARG_ID = "id"
        /** Prefill a new rule: this merchant… */
        const val ARG_PAYEE = "payee"
        /** …gets this category. */
        const val ARG_CATEGORY = "category"
    }
}
