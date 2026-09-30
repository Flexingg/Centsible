package app.centsible.feature.budget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.centsible.core.designsystem.component.CategoryEmoji
import app.centsible.core.domain.PersonalGateway
import app.centsible.core.domain.SessionStore
import app.centsible.core.model.Appearance
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.centsible.core.designsystem.component.CentsibleCard
import app.centsible.core.designsystem.component.Loadable
import app.centsible.core.designsystem.component.LoadingState
import app.centsible.core.designsystem.component.MessageState
import app.centsible.core.designsystem.component.PickerItem
import app.centsible.core.designsystem.component.PickerSheet
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.domain.BudgetChanges
import app.centsible.core.domain.BudgetEngine
import app.centsible.core.domain.SelectedBudget
import app.centsible.core.domain.userMessage
import app.centsible.core.model.Category
import app.centsible.core.model.CategoryGroup
import app.centsible.core.model.CategoryGroupId
import app.centsible.core.model.CategoryId
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CategoryManagerUiState(
    val groups: Loadable<List<CategoryGroup>> = Loadable.Loading,
    /** Colors and emoji, shared by the household (keyed by group or category id). */
    val looks: Map<String, Appearance> = emptyMap(),
    val canEdit: Boolean = true,
    val message: String? = null,
)

@HiltViewModel
class CategoryManagerViewModel @Inject constructor(
    private val engine: BudgetEngine,
    private val selectedBudget: SelectedBudget,
    private val personal: PersonalGateway,
    private val sessions: SessionStore,
    changes: BudgetChanges,
) : ViewModel() {
    private val state = MutableStateFlow(CategoryManagerUiState())
    val uiState: StateFlow<CategoryManagerUiState> = state.asStateFlow()

    init {
        refresh()
        viewModelScope.launch { changes.changes.collect { refresh() } }
    }

    fun refresh() = viewModelScope.launch {
        runCatching {
            coroutineScope {
                val b = selectedBudget()
                val groups = async { engine.categoryGroups(b) }
                val looks = async { runCatching { personal.appearance(b) }.getOrDefault(emptyMap()) }
                groups.await() to looks.await()
            }
        }
            .onSuccess { (g, l) -> state.update { it.copy(groups = Loadable.Ready(g), looks = l, canEdit = sessions.current()?.member?.role?.canWrite != false) } }
            .onFailure { e -> state.update { it.copy(groups = Loadable.Failed(e.userMessage())) } }
    }

    fun saveLook(id: String, appearance: Appearance) {
        val clear = appearance.color == null && appearance.emoji == null
        state.update { it.copy(looks = if (clear) it.looks - id else it.looks + (id to appearance)) }
        viewModelScope.launch {
            runCatching { personal.setAppearance(selectedBudget(), id, appearance) }
                .onFailure { e -> state.update { it.copy(message = e.userMessage()) }; refresh() }
        }
    }

    fun addGroup(name: String) = act { engine.createCategoryGroup(selectedBudget(), name.trim()) }
    fun renameGroup(id: CategoryGroupId, name: String) = act { engine.updateCategoryGroup(selectedBudget(), id, name = name.trim()) }
    fun setGroupHidden(id: CategoryGroupId, hidden: Boolean) = act { engine.updateCategoryGroup(selectedBudget(), id, hidden = hidden) }
    fun deleteGroup(id: CategoryGroupId, moveTo: CategoryId?) = act { engine.deleteCategoryGroup(selectedBudget(), id, moveTo) }
    fun addCategory(group: CategoryGroupId, name: String) = act { engine.createCategory(selectedBudget(), name.trim(), group) }
    fun renameCategory(id: CategoryId, name: String) = act { engine.updateCategory(selectedBudget(), id, name = name.trim()) }
    fun setCategoryHidden(id: CategoryId, hidden: Boolean) = act { engine.updateCategory(selectedBudget(), id, hidden = hidden) }
    fun deleteCategory(id: CategoryId, moveTo: CategoryId?) = act { engine.deleteCategory(selectedBudget(), id, moveTo) }
    fun messageShown() = state.update { it.copy(message = null) }

    private fun act(block: suspend () -> Unit) = viewModelScope.launch {
        runCatching { block() }.onFailure { e -> state.update { it.copy(message = e.userMessage()) } }
    }
}

private sealed interface Dialog {
    data object NewGroup : Dialog
    data class NewCategory(val group: CategoryGroup?) : Dialog
    data class RenameGroup(val group: CategoryGroup) : Dialog
    data class RenameCategory(val category: Category) : Dialog
    data class DeleteCategory(val category: Category) : Dialog
    data class DeleteGroup(val group: CategoryGroup) : Dialog
    data class Look(val item: Lookable) : Dialog
}

/** What the Categories screen can do (defaults are no-ops, for previews and tests). */
data class CategoryManagerActions(
    val back: () -> Unit = {},
    val retry: () -> Unit = {},
    val addGroup: (String) -> Unit = {},
    val renameGroup: (CategoryGroupId, String) -> Unit = { _, _ -> },
    val setGroupHidden: (CategoryGroupId, Boolean) -> Unit = { _, _ -> },
    val deleteGroup: (CategoryGroupId, CategoryId?) -> Unit = { _, _ -> },
    val addCategory: (CategoryGroupId, String) -> Unit = { _, _ -> },
    val renameCategory: (CategoryId, String) -> Unit = { _, _ -> },
    val setCategoryHidden: (CategoryId, Boolean) -> Unit = { _, _ -> },
    val deleteCategory: (CategoryId, CategoryId?) -> Unit = { _, _ -> },
    val saveLook: (String, Appearance) -> Unit = { _, _ -> },
    val messageShown: () -> Unit = {},
)

@Composable
fun CategoryManagerRoute(onBack: () -> Unit, viewModel: CategoryManagerViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    CategoryManagerScreen(
        state,
        CategoryManagerActions(
            back = onBack,
            retry = { viewModel.refresh() },
            addGroup = viewModel::addGroup,
            renameGroup = viewModel::renameGroup,
            setGroupHidden = viewModel::setGroupHidden,
            deleteGroup = viewModel::deleteGroup,
            addCategory = viewModel::addCategory,
            renameCategory = viewModel::renameCategory,
            setCategoryHidden = viewModel::setCategoryHidden,
            deleteCategory = viewModel::deleteCategory,
            saveLook = viewModel::saveLook,
            messageShown = viewModel::messageShown,
        ),
    )
}

/**
 * Categories: everything about them in one place. Create, rename, hide and delete (moving
 * what was in it), and each one's color and emoji, which the whole household sees.
 */
@Composable
fun CategoryManagerScreen(state: CategoryManagerUiState, actions: CategoryManagerActions) {
    val colors = CentsibleTheme.colors
    val snackbar = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); actions.messageShown() } }
    val allCategories = state.groups.valueOrNull.orEmpty().flatMap { g -> g.categories.map { g to it } }
    val edit = state.canEdit

    Scaffold(
        containerColor = colors.canvas,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = actions.back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                Text("Categories", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                if (edit) TextButton(onClick = { dialog = Dialog.NewGroup }) { Text("Add group") }
            }
        },
        floatingActionButton = {
            if (edit && state.groups is Loadable.Ready) {
                ExtendedFloatingActionButton(
                    onClick = { dialog = Dialog.NewCategory(null) },
                    modifier = Modifier.semantics { contentDescription = "New category" },
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    text = { Text("New category") },
                )
            }
        },
    ) { padding ->
        when (val g = state.groups) {
            Loadable.Loading -> LoadingState(Modifier.padding(padding))
            is Loadable.Failed -> MessageState("Couldn't load categories", g.message, actionLabel = "Try again", onAction = actions.retry, modifier = Modifier.padding(padding))
            is Loadable.Ready -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text(
                        "Tap one to rename it, its icon to pick a color and emoji. Colors show on Home's dial (groups), in reports and next to every transaction, for everyone in the household.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
                items(g.value.sortedBy { it.isIncome }, key = { it.id.raw }) { group ->
                    CentsibleCard(contentPadding = PaddingValues(0.dp)) {
                        val groupLook = Lookable(group.id.raw, group.name, isGroup = true)
                        Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            LookIcon(groupLook, state.looks[group.id.raw], enabled = edit) { dialog = Dialog.Look(groupLook) }
                            Spacer(Modifier.width(10.dp))
                            Text(
                                group.name + if (group.hidden) " · hidden" else "",
                                style = MaterialTheme.typography.titleSmall,
                                color = if (group.hidden) colors.textTertiary else colors.textPrimary,
                                modifier = Modifier.weight(1f),
                            )
                            if (edit) {
                                RowMenu(
                                    listOfNotNull(
                                        "Add category" to { dialog = Dialog.NewCategory(group) },
                                        "Rename" to { dialog = Dialog.RenameGroup(group) },
                                        "Color & emoji" to { dialog = Dialog.Look(groupLook) },
                                        (if (group.hidden) "Show" else "Hide") to { actions.setGroupHidden(group.id, !group.hidden) },
                                        if (!group.isIncome) "Delete group" to { dialog = Dialog.DeleteGroup(group) } else null,
                                    ),
                                )
                            }
                        }
                        group.categories.forEach { c ->
                            val look = Lookable(c.id.raw, c.name, isGroup = false)
                            HorizontalDivider(color = colors.border)
                            Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                LookIcon(look, state.looks[c.id.raw], enabled = edit) { dialog = Dialog.Look(look) }
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    c.name + if (c.hidden) " · hidden" else "",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (c.hidden) colors.textTertiary else colors.textPrimary,
                                    modifier = Modifier.weight(1f).clickable(enabled = edit) { dialog = Dialog.RenameCategory(c) }.padding(vertical = 12.dp),
                                )
                                if (edit) {
                                    RowMenu(
                                        listOf(
                                            "Rename" to { dialog = Dialog.RenameCategory(c) },
                                            "Color & emoji" to { dialog = Dialog.Look(look) },
                                            (if (c.hidden) "Show" else "Hide") to { actions.setCategoryHidden(c.id, !c.hidden) },
                                            "Delete" to { dialog = Dialog.DeleteCategory(c) },
                                        ),
                                    )
                                } else {
                                    Spacer(Modifier.height(48.dp))
                                }
                            }
                        }
                    }
                }
                item {
                    Text(
                        "Hidden categories keep their history and budgets but disappear from the budget screen and pickers.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textTertiary,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }
    }

    when (val d = dialog) {
        Dialog.NewGroup -> NameDialog("New category group", "", onDismiss = { dialog = null }) { actions.addGroup(it); dialog = null }
        is Dialog.NewCategory -> {
            val groups = state.groups.valueOrNull.orEmpty().filter { !it.hidden }
            NewCategoryDialog(groups, d.group, onDismiss = { dialog = null }) { group, name -> actions.addCategory(group, name); dialog = null }
        }
        is Dialog.RenameGroup -> NameDialog("Rename group", d.group.name, onDismiss = { dialog = null }) { actions.renameGroup(d.group.id, it); dialog = null }
        is Dialog.RenameCategory -> NameDialog("Rename category", d.category.name, onDismiss = { dialog = null }) { actions.renameCategory(d.category.id, it); dialog = null }
        is Dialog.Look -> LookDialog(d.item, state.looks[d.item.id], onDismiss = { dialog = null }, onSave = { actions.saveLook(d.item.id, it); dialog = null })
        is Dialog.DeleteCategory -> {
            val targets = allCategories.filter { (g, c) -> c.id != d.category.id && g.isIncome == d.category.isIncome && !c.hidden }
            PickerSheet(
                title = "Delete ${d.category.name}: move its transactions and budget to…",
                items = targets.map { (g, c) -> PickerItem(c.id.raw, c.name, section = g.name, emoji = true) },
                selectedKey = null,
                onDismiss = { dialog = null },
                onPick = { actions.deleteCategory(d.category.id, CategoryId(it.key)); dialog = null },
            )
        }
        is Dialog.DeleteGroup -> {
            val targets = allCategories.filter { (g, c) -> g.id != d.group.id && !g.isIncome && !c.hidden }
            PickerSheet(
                title = "Delete ${d.group.name}: move its transactions and budgets to…",
                items = targets.map { (g, c) -> PickerItem(c.id.raw, c.name, section = g.name, emoji = true) },
                selectedKey = null,
                onDismiss = { dialog = null },
                onPick = { actions.deleteGroup(d.group.id, CategoryId(it.key)); dialog = null },
            )
        }
        null -> Unit
    }
}

/** The category's (or group's) icon with its household color; tapping it edits the look. */
@Composable
private fun LookIcon(item: Lookable, look: Appearance?, enabled: Boolean, onClick: () -> Unit) {
    val colors = CentsibleTheme.colors
    val color = look?.color?.let { Color(it) }
    Box(
        Modifier.size(48.dp).clip(CircleShape)
            .clickable(enabled = enabled, onClickLabel = "Change color and emoji", onClick = onClick)
            .semantics { contentDescription = "${item.name} icon" },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(32.dp).background(color?.copy(alpha = 0.22f) ?: colors.cardMuted, CircleShape), contentAlignment = Alignment.Center) {
            if (item.isGroup && look?.emoji == null) Box(Modifier.size(12.dp).background(color ?: colors.textTertiary, CircleShape))
            else Text(look?.emoji ?: CategoryEmoji.forName(item.name), fontSize = 16.sp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewCategoryDialog(groups: List<CategoryGroup>, initial: CategoryGroup?, onDismiss: () -> Unit, onSave: (CategoryGroupId, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var group by remember { mutableStateOf(initial ?: groups.firstOrNull { !it.isIncome } ?: groups.firstOrNull()) }
    var open by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New category") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
                    OutlinedTextField(
                        group?.name.orEmpty(), {},
                        readOnly = true,
                        label = { Text("Group") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        groups.forEach { g -> DropdownMenuItem(text = { Text(g.name) }, onClick = { group = g; open = false }) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { group?.let { onSave(it.id, name) } }, enabled = name.isNotBlank() && group != null) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RowMenu(items: List<Pair<String, () -> Unit>>) {
    var open by remember { mutableStateOf(false) }
    Column {
        IconButton(onClick = { open = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEach { (label, action) -> DropdownMenuItem(text = { Text(label) }, onClick = { open = false; action() }) }
        }
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank() && name.trim() != initial) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
