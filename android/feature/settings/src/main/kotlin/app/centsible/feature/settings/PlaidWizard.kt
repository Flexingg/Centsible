package app.centsible.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.centsible.core.designsystem.component.CentsibleCard
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.model.PlaidStatus

/**
 * Plaid setup, one step at a time: make the free account, paste the keys, allow the
 * Centsible app (so bank sign-ins that leave for the bank's own app or site come back),
 * then connect the first bank. Each step says where to tap in Plaid's dashboard, because
 * that part happens outside Centsible.
 */
@Composable
internal fun PlaidWizard(step: PlaidStep, status: PlaidStatus, state: PlaidUiState, actions: PlaidActions, modifier: Modifier = Modifier) {
    val colors = CentsibleTheme.colors
    val steps = PlaidStep.entries
    val index = steps.indexOf(step)
    Column(modifier.fillMaxSize().navigationBarsPadding()) {
        LinearProgressIndicator(
            progress = { (index + 1f) / steps.size },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            color = colors.accent,
            trackColor = colors.border,
        )
        Text("Step ${index + 1} of ${steps.size}", style = MaterialTheme.typography.labelMedium, color = colors.textTertiary, modifier = Modifier.padding(horizontal = 16.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (step) {
                PlaidStep.Account -> AccountStep()
                PlaidStep.Keys -> KeysStep(status, state, actions)
                PlaidStep.AllowApp -> AllowAppStep(status)
                PlaidStep.ConnectBank -> ConnectStep()
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val back = steps.getOrNull(index - 1)?.takeIf { step == PlaidStep.Keys || (step == PlaidStep.AllowApp && !status.configured) }
            if (back != null) OutlinedButton(onClick = { actions.wizardTo(back) }, modifier = Modifier.weight(1f)) { Text("Back") }
            when (step) {
                PlaidStep.Account -> Button(onClick = { actions.wizardTo(PlaidStep.Keys) }, modifier = Modifier.weight(1f)) { Text("I have an account") }
                PlaidStep.Keys -> Button(
                    onClick = { actions.saveSetup(true) },
                    enabled = state.form.clientId.isNotBlank() && (status.configured || state.form.secret.isNotBlank()) && !state.busy,
                    modifier = Modifier.weight(1f),
                ) { Text(if (state.busy) "Checking with Plaid…" else "Save to my bridge") }
                PlaidStep.AllowApp -> Button(onClick = { actions.wizardTo(PlaidStep.ConnectBank) }, modifier = Modifier.weight(1f)) { Text("Done") }
                PlaidStep.ConnectBank -> {
                    OutlinedButton(onClick = { actions.wizardTo(null) }, modifier = Modifier.weight(1f)) { Text("Later") }
                    Button(onClick = { actions.connect(null) }, enabled = state.canEdit && !state.busy, modifier = Modifier.weight(1f)) {
                        Text(if (state.busy) "Opening Plaid…" else "Connect a bank")
                    }
                }
            }
        }
    }
}

@Composable
private fun StepTitle(title: String, body: String) {
    Text(title, style = MaterialTheme.typography.titleLarge)
    Text(body, style = MaterialTheme.typography.bodyMedium, color = CentsibleTheme.colors.textSecondary)
}

@Composable
private fun Hint(n: Int, text: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text("$n.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(22.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun AccountStep() {
    val uri = LocalUriHandler.current
    StepTitle(
        "Make a free Plaid account",
        "Plaid connects to your bank for Centsible, as a second option beside SimpleFIN. For a household it's free: Plaid's Trial plan covers up to 10 bank logins with real banks, including ones that sign in through their own app (Wells Fargo, Chase, Discover…).",
    )
    CentsibleCard {
        Text("You'll need about 5 minutes. A computer is easiest for the Plaid dashboard, but a phone works.", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        Hint(1, "Open dashboard.plaid.com and sign up. It's for developers in the US and Canada.")
        Hint(2, "Confirm your email. If Plaid asks what you're building, a personal budgeting app is the honest answer.")
        Hint(3, "Check the dashboard shows the Trial plan (free, no billing details needed).")
    }
    Button(onClick = { uri.openUri("https://dashboard.plaid.com/signup") }, modifier = Modifier.fillMaxWidth()) { Text("Open the Plaid dashboard") }
}

@Composable
private fun KeysStep(status: PlaidStatus, state: PlaidUiState, actions: PlaidActions) {
    val uri = LocalUriHandler.current
    StepTitle("Copy your keys", "They let your bridge talk to Plaid. The client id isn't secret; the secret is, so it only goes to your bridge.")
    CentsibleCard {
        Hint(1, "In the Plaid dashboard, open Developers → Keys.")
        Hint(2, "Copy the client_id and paste it below.")
        Hint(3, "Copy the Production secret (for real banks) and paste it below.")
    }
    TextButton(onClick = { uri.openUri("https://dashboard.plaid.com/developers/keys") }) { Text("Open Developers → Keys") }
    PlaidKeysForm(status, state, actions)
}

@Composable
private fun AllowAppStep(status: PlaidStatus) {
    val colors = CentsibleTheme.colors
    val uri = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    StepTitle(
        "Allow the Centsible app",
        "Many banks sign you in through their own app or website and then hand you back. Plaid only hands back to apps you've allowed, so add Centsible's package name once.",
    )
    CentsibleCard {
        Hint(1, "In the Plaid dashboard, open Developers → API.")
        Hint(2, "Under Allowed Android package names, choose Add and paste:")
        Row(Modifier.fillMaxWidth().padding(start = 22.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(status.androidPackageName, style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.weight(1f))
            TextButton(onClick = { clipboard.setText(AnnotatedString(status.androidPackageName)) }) { Text("Copy") }
        }
        Hint(3, "Save changes, then come back here.")
    }
    TextButton(onClick = { uri.openUri("https://dashboard.plaid.com/developers/api") }) { Text("Open Developers → API") }
    Text(
        "Without this, connecting a bank stops with a message about the package name; add it and try again.",
        style = MaterialTheme.typography.bodySmall,
        color = colors.textSecondary,
    )
}

@Composable
private fun ConnectStep() {
    StepTitle(
        "Connect your first bank",
        "Plaid opens its own sign-in screens for your bank. Centsible never sees your bank password, only the access you grant.",
    )
    CentsibleCard {
        Hint(1, "Tap Connect a bank and find your bank (Wells Fargo, Discover, …).")
        Hint(2, "Sign in. Some banks open their own app or page and ask for a code, then hand you back.")
        Hint(3, "Pick the accounts to share. Back here, tap each one to link it to an account in your budget.")
    }
    Text(
        "Accounts that sync through SimpleFIN now move over to Plaid when you link them. What's already imported is matched, not duplicated.",
        style = MaterialTheme.typography.bodySmall,
        color = CentsibleTheme.colors.textSecondary,
    )
}
