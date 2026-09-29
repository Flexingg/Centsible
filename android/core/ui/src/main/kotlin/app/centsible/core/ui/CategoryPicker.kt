package app.centsible.core.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.centsible.core.designsystem.component.CategoryAvatar
import app.centsible.core.designsystem.component.StatLabel
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.domain.BudgetEngine
import app.centsible.core.domain.SelectedBudget
import app.centsible.core.domain.SessionStore
import app.centsible.core.domain.userMessage
import app.centsible.core.model.CategoryGroup
import app.centsible.core.model.CategoryGroupId
import app.centsible.core.model.CategoryId
import app.centsible.core.model.Feature
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CategoryPickerState(
    val groups: List<CategoryGroup> = emptyList(),
    val loading: Boolean = true,
    /** May create, rename and hide categories. */
    val canEdit: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
)

/** Loads categories and makes the changes the picker offers (the whole app reloads after). */
@HiltViewModel
class CategoryPickerViewModel @Inject constructor(
    private val engine: BudgetEngine,
    private val selectedBudget: SelectedBudget,
    private val sessions: SessionStore,
) : ViewModel() {
    private val state = MutableStateFlow(CategoryPickerState())
    val uiState: StateFlow<CategoryPickerState> = state.asStateFlow()

    fun load() = viewModelScope.launch {
        runCatching {
            val b = selectedBudget()
            val groups = async { engine.categoryGroups(b) }
            val caps = async { engine.capabilities() }
            groups.await() to (sessions.current()?.member?.role?.canWrite == true && caps.await().has(Feature.CategoriesWrite))
        }
            .onSuccess { (g, canEdit) -> state.update { it.copy(groups = g, canEdit = canEdit, loading = false) } }
            .onFailure { e -> state.update { it.copy(loading = false, error = e.userMessage()) } }
    }

    /** Creates the category (and its group if new), then hands back its id. */
    fun create(name: String, group: CategoryGroupId?, newGroupName: String?, onCreated: (CategoryId) -> Unit) {
        state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            runCatching {
                val b = selectedBudget()
                val groupId = group ?: engine.createCategoryGroup(b, newGroupName!!.trim()).id
                engine.createCategory(b, name.trim(), groupId).id
            }
                .onSuccess { id -> state.update { it.copy(busy = false) }; load(); onCreated(id) }
                .onFailure { e -> state.update { it.copy(busy = false, error = e.userMessage()) } }
        }
    }

    fun rename(id: CategoryId, name: String) = change { engine.updateCategory(selectedBudget(), id, name = name.trim()) }
    fun hide(id: CategoryId) = change { engine.updateCategory(selectedBudget(), id, hidden = true) }

    private fun change(block: suspend () -> Unit) {
        state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            runCatching { block() }
                .onSuccess { state.update { it.copy(busy = false) }; load() }
                .onFailure { e -> state.update { it.copy(busy = false, error = e.userMessage()) } }
        }
    }
}

/**
 * Choose a category, and create or tidy categories without leaving: "New category"
 * (in an existing or new group), and rename or hide from each row. A newly created
 * category is chosen straight away.
 */
@Composable
fun CategoryPickerSheet(
    title: String = "Category",
    selected: CategoryId?,
    onPick: (CategoryId?) -> Unit,
    onDismiss: () -> Unit,
    /** Offer "No category" (clears it). */
    allowNone: Boolean = false,
    includeIncome: Boolean = true,
    /** Already chosen elsewhere (a multi-pick): left out. */
    exclude: Set<CategoryId> = emptySet(),
    /** Open straight into "New category". */
    startCreating: Boolean = false,
    /** Also told the chosen category's name and group (for screens that don't reload categories). */
    onPickNamed: (CategoryId, String, String) -> Unit = { _, _, _ -> },
    viewModel: CategoryPickerViewModel = hiltViewModel(key = "category-picker"),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.load() }
    fun pick(id: CategoryId?, name: String? = null, group: String? = null) {
        if (id != null) {
            val g = state.groups.firstOrNull { gr -> gr.categories.any { it.id == id } }
            val n = name ?: g?.categories?.firstOrNull { it.id == id }?.name
            if (n != null) onPickNamed(id, n, group ?: g?.name.orEmpty())
        }
        onPick(id)
    }
    CategoryPickerContent(
        title, state, selected, allowNone, includeIncome, exclude,
        onPick = { pick(it) },
        onDismiss = onDismiss,
        onCreate = { name, group, newGroup ->
            val groupName = newGroup ?: state.groups.firstOrNull { it.id == group }?.name.orEmpty()
            viewModel.create(name, group, newGroup) { pick(it, name.trim(), groupName) }
        },
        onRename = viewModel::rename,
        onHide = viewModel::hide,
        startCreating = startCreating,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryPickerContent(
    title: String,
    state: CategoryPickerState,
    selected: CategoryId?,
    allowNone: Boolean,
    includeIncome: Boolean,
    exclude: Set<CategoryId>,
    onPick: (CategoryId?) -> Unit,
    onDismiss: () -> Unit,
    onCreate: (String, CategoryGroupId?, String?) -> Unit,
    onRename: (CategoryId, String) -> Unit,
    onHide: (CategoryId) -> Unit,
    startCreating: Boolean = false,
) {
    val colors = CentsibleTheme.colors
    var query by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(startCreating) }
    var editing by remember { mutableStateOf<CategoryId?>(null) }
    val groups = state.groups.filter { !it.hidden && (includeIncome || !it.isIncome) }
    val q = query.trim()
    val matches = groups.map { g -> g to g.categories.filter { !it.hidden && it.id !in exclude && (q.isEmpty() || it.name.contains(q, ignoreCase = true)) } }
        .filter { it.second.isNotEmpty() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.card) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().animateContentSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (state.canEdit && !creating) {
                    TextButton(onClick = { creating = true; editing = null }) {
                        Icon(Icons.Rounded.Add, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("New category")
                    }
                }
            }
            state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.negative, modifier = Modifier.padding(bottom = 6.dp)) }
            if (creating) {
                NewCategory(
                    initialName = q,
                    groups = groups,
                    preferredGroup = groups.firstOrNull { g -> g.categories.any { it.id == selected } }?.id,
                    busy = state.busy,
                    onCancel = { creating = false },
                    onCreate = { name, group, newGroup -> onCreate(name, group, newGroup) },
                )
                Spacer(Modifier.width(8.dp))
            } else {
                OutlinedTextField(
                    query, { query = it },
                    placeholder = { Text("Search categories") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                )
            }
            if (state.loading) {
                Text("Loading…", style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary, modifier = Modifier.padding(vertical = 16.dp))
            }
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                if (allowNone && q.isEmpty()) {
                    item(key = "none") { PickRow("No category", null, selected == null, onClick = { onPick(null) }) }
                }
                matches.forEach { (g, cats) ->
                    item(key = "g-${g.id.raw}") { StatLabel(g.name, Modifier.padding(top = 14.dp, bottom = 4.dp)) }
                    cats.forEach { c ->
                        item(key = c.id.raw) {
                            if (editing == c.id) {
                                EditRow(c.name, state.busy, onSave = { onRename(c.id, it); editing = null }, onHide = { onHide(c.id); editing = null }, onCancel = { editing = null })
                            } else {
                                PickRow(
                                    c.name, c.name, c.id == selected,
                                    onClick = { onPick(c.id) },
                                    onEdit = if (state.canEdit) ({ editing = c.id; creating = false }) else null,
                                )
                            }
                        }
                    }
                }
                // Nothing matches: offer to make it.
                if (!state.loading && q.isNotEmpty() && matches.isEmpty() && state.canEdit && !creating) {
                    item(key = "create") {
                        Row(
                            Modifier.fillMaxWidth().clickable { creating = true }.padding(vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = null, tint = colors.accent)
                            Spacer(Modifier.width(10.dp))
                            Text("Create “$q”", style = MaterialTheme.typography.bodyLarge, color = colors.accent)
                        }
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
        }
    }
}

@Composable
private fun PickRow(label: String, avatarName: String?, isSelected: Boolean, onClick: () -> Unit, onEdit: (() -> Unit)? = null) {
    val colors = CentsibleTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.RadioButton, onClick = onClick).semantics { selected = isSelected }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (avatarName != null) CategoryAvatar(avatarName, size = 32.dp) else Box(Modifier.width(32.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (isSelected) Icon(Icons.Rounded.Check, contentDescription = null, tint = colors.accent)
        onEdit?.let { IconButton(onClick = it) { Icon(Icons.Rounded.Edit, contentDescription = "Rename or hide $label", tint = colors.textTertiary) } }
    }
}

@Composable
private fun EditRow(name: String, busy: Boolean, onSave: (String) -> Unit, onHide: () -> Unit, onCancel: () -> Unit) {
    var text by remember { mutableStateOf(name) }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            text, { text = it },
            label = { Text("Name") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank() && text != name) onSave(text) }),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onHide, enabled = !busy) { Text("Hide", color = CentsibleTheme.colors.negative) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text("Cancel") }
            Button(onClick = { onSave(text) }, enabled = !busy && text.isNotBlank() && text.trim() != name) { Text("Save") }
        }
    }
}

@Composable
private fun NewCategory(
    initialName: String,
    groups: List<CategoryGroup>,
    preferredGroup: CategoryGroupId?,
    busy: Boolean,
    onCancel: () -> Unit,
    onCreate: (String, CategoryGroupId?, String?) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    // Where it goes: an existing group, or (null) a new one named below.
    var group by remember { mutableStateOf(preferredGroup ?: groups.firstOrNull { !it.isIncome }?.id ?: groups.firstOrNull()?.id) }
    var newGroup by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    val groupLabel = group?.let { id -> groups.firstOrNull { it.id == id }?.name } ?: "New group"
    val ready = name.isNotBlank() && (group != null || newGroup.isNotBlank())
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            name, { name = it },
            label = { Text("New category") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (ready) onCreate(name, group, newGroup.takeIf { group == null }) }),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("In", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Box {
                OutlinedButton(onClick = { menu = true }) { Text(groupLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    groups.forEach { g -> DropdownMenuItem(text = { Text(g.name) }, onClick = { group = g.id; menu = false }) }
                    DropdownMenuItem(text = { Text("New group…") }, leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null) }, onClick = { group = null; menu = false })
                }
            }
        }
        if (group == null) {
            OutlinedTextField(
                newGroup, { newGroup = it },
                label = { Text("New group name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text("Cancel") }
            Button(onClick = { onCreate(name, group, newGroup.takeIf { group == null }) }, enabled = ready && !busy) {
                Text(if (busy) "Creating…" else "Create and choose")
            }
        }
    }
}
