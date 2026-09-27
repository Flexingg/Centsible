package app.canopy.feature.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.canopy.core.designsystem.component.CanopyCard
import app.canopy.core.designsystem.component.Loadable
import app.canopy.core.designsystem.component.LoadingState
import app.canopy.core.designsystem.component.MerchantAvatar
import app.canopy.core.designsystem.component.MessageState
import app.canopy.core.designsystem.component.SectionCard
import app.canopy.core.designsystem.theme.CanopyTheme
import app.canopy.core.model.BridgeStatus
import app.canopy.core.model.Member
import app.canopy.core.model.PairingInvite
import app.canopy.core.model.Role

@Composable
fun SettingsRoute(viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SettingsScreen(
        state = state,
        actions = SettingsActions(
            retry = { viewModel.refresh() },
            addMember = { name, role -> viewModel.addMember(name, role) },
            invite = { viewModel.invite(it) },
            grantBudget = { viewModel.grantCurrentBudget(it.id) },
            revokeDevice = { viewModel.revoke(it) },
            dismissInvite = viewModel::dismissInvite,
            signOut = { viewModel.signOut() },
            switchBudget = { viewModel.switchBudget(it) },
            messageShown = viewModel::messageShown,
        ),
    )
}

data class SettingsActions(
    val retry: () -> Unit = {},
    val addMember: (String, Role) -> Unit = { _, _ -> },
    val invite: (Member) -> Unit = {},
    val grantBudget: (Member) -> Unit = {},
    val revokeDevice: (app.canopy.core.model.DeviceId) -> Unit = {},
    val dismissInvite: () -> Unit = {},
    val signOut: () -> Unit = {},
    val switchBudget: (app.canopy.core.model.BudgetId) -> Unit = {},
    val messageShown: () -> Unit = {},
)

@Composable
fun SettingsScreen(state: SettingsUiState, actions: SettingsActions, renderQr: Boolean = true) {
    val colors = CanopyTheme.colors
    val snackbar = remember { SnackbarHostState() }
    var adding by remember { mutableStateOf(false) }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); actions.messageShown() } }

    Scaffold(containerColor = colors.canvas, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        when (val data = state.data) {
            Loadable.Loading -> LoadingState(Modifier.padding(padding))
            is Loadable.Failed -> MessageState("Couldn't load settings", data.message, actionLabel = "Try again", onAction = actions.retry, modifier = Modifier.padding(padding))
            is Loadable.Ready -> {
                val d = data.value
                val isOwner = d.me.member.role == Role.Owner
                LazyColumn(
                    Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item { Text("Household & settings", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 4.dp)) }
                    item {
                        CanopyCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                MerchantAvatar(d.me.member.displayName, size = 48.dp)
                                Spacer(Modifier.width(14.dp))
                                Column {
                                    Text(d.me.member.displayName, style = MaterialTheme.typography.titleMedium)
                                    Text("${d.me.member.role.label()} · ${d.me.device.name}", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                                }
                            }
                        }
                    }
                    item {
                        SectionCard("Household", action = if (isOwner) "Add person" else null, onAction = { adding = true }) {
                            d.members.forEachIndexed { i, m ->
                                if (i > 0) HorizontalDivider(color = colors.border)
                                MemberRow(m, isOwner = isOwner, isMe = m.id == d.me.member.id, onInvite = { actions.invite(m) }, onGrant = { actions.grantBudget(m) })
                            }
                            if (!isOwner) Text("Only owners can invite people.", style = MaterialTheme.typography.bodySmall, color = colors.textTertiary)
                        }
                    }
                    item {
                        SectionCard("Your devices") {
                            d.me.devices.forEach { dev ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(dev.name + if (dev.id == d.me.device.id) " (this device)" else "", style = MaterialTheme.typography.bodyLarge)
                                        Text("Last seen ${dev.lastSeenAt?.take(10) ?: "never"}", style = MaterialTheme.typography.labelSmall, color = colors.textTertiary)
                                    }
                                    if (dev.id != d.me.device.id && isOwner) TextButton(onClick = { actions.revokeDevice(dev.id) }) { Text("Remove") }
                                }
                            }
                        }
                    }
                    if (d.budgets.size > 1) {
                        item {
                            SectionCard("Budget") {
                                d.budgets.forEach { b ->
                                    Row(
                                        Modifier.fillMaxWidth().clickable { actions.switchBudget(b.id) }.padding(vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(b.name + if (b.encrypted) " 🔒" else "", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                                        if (b.id == d.selectedBudget) Text("Current", style = MaterialTheme.typography.labelLarge, color = colors.accent)
                                    }
                                }
                            }
                        }
                    }
                    item { ConnectionCard(d) }
                    item {
                        TextButton(onClick = actions.signOut, modifier = Modifier.fillMaxWidth()) {
                            Text("Sign out of this device", color = colors.negative)
                        }
                    }
                }
            }
        }
    }

    if (adding) AddMemberDialog(onDismiss = { adding = false }, onAdd = { name, role -> adding = false; actions.addMember(name, role) })
    state.invite?.let { (member, invite) -> InviteDialog(member, invite, renderQr, actions.dismissInvite) }
}

private fun Role.label() = when (this) {
    Role.Owner -> "Owner"
    Role.Member -> "Member"
    Role.Viewer -> "Viewer"
    Role.Unknown -> "Member"
}

@Composable
private fun MemberRow(m: Member, isOwner: Boolean, isMe: Boolean, onInvite: () -> Unit, onGrant: () -> Unit) {
    val colors = CanopyTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        MerchantAvatar(m.displayName)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(m.displayName + if (isMe) " (you)" else "", style = MaterialTheme.typography.bodyLarge)
            val access = if (m.role == Role.Owner) "All budgets" else "${m.budgetIds.size} budget${if (m.budgetIds.size == 1) "" else "s"}"
            Text("${m.role.label()} · $access", style = MaterialTheme.typography.labelSmall, color = colors.textTertiary)
        }
        if (isOwner && !isMe) {
            if (m.role != Role.Owner && m.budgetIds.isEmpty()) TextButton(onClick = onGrant) { Text("Share budget") }
            TextButton(onClick = onInvite) { Text("Invite") }
        }
    }
}

@Composable
private fun ConnectionCard(d: SettingsData) {
    val colors = CanopyTheme.colors
    val caps = d.capabilities
    val (dot, label) = when (caps.status) {
        BridgeStatus.Ok -> colors.positive to "Connected"
        BridgeStatus.Degraded -> colors.warning to "Connected, some features unavailable"
        BridgeStatus.Unavailable -> colors.negative to "Bridge can't reach Actual"
        BridgeStatus.Unknown -> colors.textTertiary to "Unknown"
    }
    SectionCard("Connection") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.size(8.dp).background(dot, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(8.dp))
        InfoRow("Bridge", d.bridgeUrl.removePrefix("https://"))
        InfoRow("Actual server", caps.actualServerVersion ?: "—")
        InfoRow("Bridge version", "${caps.bridgeVersion} · API ${caps.actualApiVersion}")
        if (caps.compatibility != "ok") InfoRow("Compatibility", caps.compatibility.replace('_', ' '))
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = CanopyTheme.colors.textSecondary, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun AddMemberDialog(onDismiss: () -> Unit, onAdd: (String, Role) -> Unit) {
    var name by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(Role.Member) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add someone to your household") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(Role.Member to "Can edit", Role.Viewer to "View only", Role.Owner to "Owner").forEach { (r, label) ->
                        FilterChip(selected = role == r, onClick = { role = r }, label = { Text(label) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(name, role) }, enabled = name.isNotBlank()) { Text("Add & show QR") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun InviteDialog(member: Member, invite: PairingInvite, renderQr: Boolean, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Invite ${member.displayName}") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Have them install Canopy and scan this code.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                if (renderQr) Image(qrBitmap(invite.pairingUri), contentDescription = "Pairing QR code", modifier = Modifier.size(220.dp))
                Spacer(Modifier.height(8.dp))
                Text(invite.code, style = MaterialTheme.typography.titleLarge)
                Text("Single use · expires in 10 minutes", style = MaterialTheme.typography.labelSmall, color = CanopyTheme.colors.textTertiary)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
