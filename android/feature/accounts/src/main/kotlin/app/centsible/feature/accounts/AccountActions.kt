package app.centsible.feature.accounts

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.centsible.core.designsystem.component.MoneyFormat
import app.centsible.core.designsystem.component.MoneyInput
import app.centsible.core.designsystem.component.MoneyText
import app.centsible.core.designsystem.component.MoneyTone
import app.centsible.core.designsystem.component.StatLabel
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.model.CsvMapping
import app.centsible.core.model.ImportOptions
import app.centsible.core.model.Money

data class AccountActions(
    val sync: () -> Unit = {},
    val import: () -> Unit = {},
    val importOptions: (ImportOptions) -> Unit = {},
    val confirmImport: () -> Unit = {},
    val cancelImport: () -> Unit = {},
    val reconcile: () -> Unit = {},
    val submitReconcile: (Money, Boolean) -> Unit = { _, _ -> },
    val cancelReconcile: () -> Unit = {},
)

/** Reads a picked document; statements are small, and the bridge caps them at 10 MB. */
internal fun readPickedFile(context: Context, uri: Uri): Pair<String, ByteArray>? = runCatching {
    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: "statement"
    val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
    name to bytes
}.getOrNull()

private val DATE_FORMATS = listOf("MM/dd/yyyy", "dd/MM/yyyy", "yyyy-MM-dd", "MM/dd/yy", "dd/MM/yy")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImportSheet(imp: PendingImport, actions: AccountActions) {
    val colors = CentsibleTheme.colors
    ModalBottomSheet(onDismissRequest = actions.cancelImport, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.card) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Import ${imp.fileName}", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val p = imp.preview
            if (imp.loading && p == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.height(20.dp))
                    Text("  Reading file…")
                }
            }
            imp.error?.let { Text(it, color = colors.negative, style = MaterialTheme.typography.bodyMedium) }
            if (p != null) {
                Text(
                    "${p.rows.size} transactions found · ${p.newCount} new" + if (p.matchedCount > 0) " · ${p.matchedCount} already in Actual" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Choice("Dates", imp.options.dateFormat ?: "Auto", listOf("Auto") + DATE_FORMATS) { f ->
                        actions.importOptions(imp.options.copy(dateFormat = f.takeIf { it != "Auto" }))
                    }
                    Text("Flip signs", style = MaterialTheme.typography.labelMedium)
                    Switch(checked = imp.options.invertAmounts, onCheckedChange = { actions.importOptions(imp.options.copy(invertAmounts = it)) })
                }
                if (p.columns.isNotEmpty()) CsvColumns(p.columns, p.mapping ?: CsvMapping()) { m -> actions.importOptions(imp.options.copy(csvMapping = m)) }
                p.errors.take(3).forEach { Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = colors.warning) }
                HorizontalDivider(color = colors.border)
                LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    items(p.rows.take(50)) { r ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Text(r.date.substring(5), style = MaterialTheme.typography.labelMedium, color = colors.textTertiary, modifier = Modifier.padding(end = 10.dp))
                            Text(r.payeeName ?: "—", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            MoneyText(r.amount, tone = MoneyTone.Signed, signed = r.amount.minor > 0, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Button(onClick = actions.confirmImport, enabled = !imp.loading && p.rows.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                    Text(if (imp.loading) "Importing…" else "Import ${p.newCount} new")
                }
                Text("Transactions that match ones already in Actual are merged, never duplicated.", style = MaterialTheme.typography.bodySmall, color = colors.textTertiary)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun CsvColumns(columns: List<String>, mapping: CsvMapping, onChange: (CsvMapping) -> Unit) {
    StatLabel("Columns")
    val none = "—"
    val opts = listOf(none) + columns
    fun v(s: String) = s.takeIf { it != none }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Choice("Date", mapping.date ?: none, opts) { onChange(mapping.copy(date = v(it))) }
        Choice("Payee", mapping.payee ?: none, opts) { onChange(mapping.copy(payee = v(it))) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Choice("Amount", mapping.amount ?: none, opts) { onChange(mapping.copy(amount = v(it))) }
        if (mapping.amount == null) {
            Choice("Out", mapping.outflow ?: none, opts) { onChange(mapping.copy(outflow = v(it))) }
            Choice("In", mapping.inflow ?: none, opts) { onChange(mapping.copy(inflow = v(it))) }
        }
    }
}

@Composable
private fun Choice(label: String, value: String, options: List<String>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        AssistChip(onClick = { open = true }, label = { Text("$label: $value", maxLines = 1) })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(o) }, onClick = { open = false; onPick(o) }) }
        }
    }
}

@Composable
internal fun ReconcileDialog(state: ReconcileState, actions: AccountActions) {
    val colors = CentsibleTheme.colors
    var input by remember { mutableStateOf("") }
    val parsed = MoneyInput.parse(input.replace("−", "-"))
    val result = state.result
    AlertDialog(
        onDismissRequest = actions.cancelReconcile,
        title = { Text(if (result == null) "Reconcile" else "Balances don't match") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val status = state.status
                if (status == null) {
                    CircularProgressIndicator()
                } else if (result == null) {
                    Text("Cleared balance in Actual: ${MoneyFormat.format(status.cleared)}", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        input, { input = it },
                        label = { Text("Statement balance") },
                        supportingText = { Text("Negative for money owed, like a card balance") },
                        prefix = { Text("$") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                } else {
                    Text(
                        "Your statement is ${MoneyFormat.format(result.difference.abs())} ${if (result.difference.isNegative) "lower" else "higher"} than the cleared balance. " +
                            "Check for missing or uncleared transactions, or add an adjustment to match.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            if (result == null) {
                TextButton(onClick = { parsed?.let { actions.submitReconcile(it, false) } }, enabled = parsed != null && !state.working) { Text("Reconcile") }
            } else {
                // The statement the result was computed from: cleared balance plus the difference.
                val statement = state.status?.let { it.cleared + result.difference }
                TextButton(onClick = { statement?.let { actions.submitReconcile(it, true) } }, enabled = statement != null && !state.working) {
                    Text("Add ${MoneyFormat.format(result.difference)} adjustment", color = colors.accent)
                }
            }
        },
        dismissButton = { TextButton(onClick = actions.cancelReconcile) { Text("Cancel") } },
    )
}
