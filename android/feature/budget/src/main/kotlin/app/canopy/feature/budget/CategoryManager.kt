package app.canopy.feature.budget

import androidx.compose.foundation.clickable
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
import app.canopy.core.designsystem.component.CanopyCard
import app.canopy.core.designsystem.component.CategoryAvatar
import app.canopy.core.designsystem.component.Loadable
import app.canopy.core.designsystem.component.LoadingState
import app.canopy.core.designsystem.component.MessageState
import app.canopy.core.designsystem.component.PickerItem
import app.canopy.core.designsystem.component.PickerSheet
import app.canopy.core.designsystem.theme.CanopyTheme
import app.canopy.core.domain.BudgetChanges
import app.canopy.core.domain.BudgetEngine
import app.canopy.core.domain.SelectedBudget
import app.canopy.core.domain.userMessage
import app.canopy.core.model.Category
import app.canopy.core.model.CategoryGroup
import app.canopy.core.model.CategoryGroupId
import app.canopy.core.model.CategoryId
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CategoryManagerUiState(val groups: Loadable<List<CategoryGroup>> = Loadable.Loading, val message: String? = null)

@HiltViewModel
class CategoryManagerViewModel @Inject constructor(
    private val engine: BudgetEngine,
    private val selectedBudget: SelectedBudget,
    changes: BudgetChanges,
) : ViewModel() {
    private val state = MutableStateFlow(CategoryManagerUiState())
    val uiState: StateFlow<CategoryManagerUiState> = state.asStateFlow()

    init {
        refresh()
        viewModelScope.launch { changes.changes.collect { refresh() } }
    }

    fun refresh() = viewModelScope.launch {
        runCatching { engine.categoryGroups(selectedBudget()) }
            .onSuccess { g -> state.update { it.copy(groups = Loadable.Ready(g)) } }
            .onFailure { e -> state.update { it.copy(groups = Loadable.Failed(e.userMessage())) } }
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
    data class NewGroup(val unit: Unit = Unit) : Dialog
    data class NewCategory(val group: CategoryGroup) : Dialog
    data class RenameGroup(val group: CategoryGroup) : Dialog
    data class RenameCategory(val category: Category) : Dialog
    data class DeleteCategory(val category: Category) : Dialog
    data class DeleteGroup(val group: CategoryGroup) : Dialog
}

@Composable
fun CategoryManagerRoute(onBack: () -> Unit, viewModel: CategoryManagerViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    CategoryManagerScreen(state, onBack, viewModel)
}

@Composable
private fun CategoryManagerScreen(state: CategoryManagerUiState, onBack: () -> Unit, vm: CategoryManagerViewModel) {
    val colors = CanopyTheme.colors
    val snackbar = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); vm.messageShown() } }
    val allCategories = state.groups.valueOrNull.orEmpty().flatMap { g -> g.categories.map { g to it } }

    Scaffold(
        containerColor = colors.canvas,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                Text("Categories", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { dialog = Dialog.NewGroup() }) { Text("Add group") }
            }
        },
    ) { padding ->
        when (val g = state.groups) {
            Loadable.Loading -> LoadingState(Modifier.padding(padding))
            is Loadable.Failed -> MessageState("Couldn't load categories", g.message, actionLabel = "Try again", onAction = { vm.refresh() }, modifier = Modifier.padding(padding))
            is Loadable.Ready -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(g.value.sortedBy { it.isIncome }, key = { it.id.raw }) { group ->
                    CanopyCard(contentPadding = PaddingValues(0.dp)) {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                group.name + if (group.hidden) " · hidden" else "",
                                style = MaterialTheme.typography.titleSmall,
                                color = if (group.hidden) colors.textTertiary else colors.textPrimary,
                                modifier = Modifier.weight(1f),
                            )
                            RowMenu(
                                listOfNotNull(
                                    "Add category" to { dialog = Dialog.NewCategory(group) },
                                    "Rename" to { dialog = Dialog.RenameGroup(group) },
                                    (if (group.hidden) "Show" else "Hide") to { vm.setGroupHidden(group.id, !group.hidden) },
                                    if (!group.isIncome) "Delete group" to { dialog = Dialog.DeleteGroup(group) } else null,
                                ),
                            )
                        }
                        group.categories.forEach { c ->
                            HorizontalDivider(color = colors.border)
                            Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                CategoryAvatar(c.name, size = 30.dp)
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    c.name + if (c.hidden) " · hidden" else "",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (c.hidden) colors.textTertiary else colors.textPrimary,
                                    modifier = Modifier.weight(1f).clickable { dialog = Dialog.RenameCategory(c) }.padding(vertical = 12.dp),
                                )
                                RowMenu(
                                    listOf(
                                        "Rename" to { dialog = Dialog.RenameCategory(c) },
                                        (if (c.hidden) "Show" else "Hide") to { vm.setCategoryHidden(c.id, !c.hidden) },
                                        "Delete" to { dialog = Dialog.DeleteCategory(c) },
                                    ),
                                )
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
        is Dialog.NewGroup -> NameDialog("New category group", "", onDismiss = { dialog = null }) { vm.addGroup(it); dialog = null }
        is Dialog.NewCategory -> NameDialog("New category in ${d.group.name}", "", onDismiss = { dialog = null }) { vm.addCategory(d.group.id, it); dialog = null }
        is Dialog.RenameGroup -> NameDialog("Rename group", d.group.name, onDismiss = { dialog = null }) { vm.renameGroup(d.group.id, it); dialog = null }
        is Dialog.RenameCategory -> NameDialog("Rename category", d.category.name, onDismiss = { dialog = null }) { vm.renameCategory(d.category.id, it); dialog = null }
        is Dialog.DeleteCategory -> {
            val targets = allCategories.filter { (g, c) -> c.id != d.category.id && g.isIncome == d.category.isIncome && !c.hidden }
            PickerSheet(
                title = "Delete ${d.category.name}: move its transactions and budget to…",
                items = targets.map { (g, c) -> PickerItem(c.id.raw, c.name, section = g.name, emoji = true) },
                selectedKey = null,
                onDismiss = { dialog = null },
                onPick = { vm.deleteCategory(d.category.id, CategoryId(it.key)); dialog = null },
            )
        }
        is Dialog.DeleteGroup -> {
            val targets = allCategories.filter { (g, c) -> g.id != d.group.id && !g.isIncome && !c.hidden }
            PickerSheet(
                title = "Delete ${d.group.name}: move its transactions and budgets to…",
                items = targets.map { (g, c) -> PickerItem(c.id.raw, c.name, section = g.name, emoji = true) },
                selectedKey = null,
                onDismiss = { dialog = null },
                onPick = { vm.deleteGroup(d.group.id, CategoryId(it.key)); dialog = null },
            )
        }
        null -> Unit
    }
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
