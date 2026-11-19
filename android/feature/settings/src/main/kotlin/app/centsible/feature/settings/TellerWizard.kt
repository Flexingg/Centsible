package app.centsible.feature.settings

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.centsible.core.designsystem.component.CentsibleCard
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.model.TellerStatus

private val APP_ID = Regex("^app_[A-Za-z0-9]+$")

/**
 * Teller setup, one step at a time: make the free account, copy the application id, pick
 * the certificate files Teller downloads, then connect the first bank. Each step says where
 * to tap in Teller's dashboard, because that part happens outside Centsible.
 */
@Composable
internal fun TellerWizard(step: TellerStep, status: TellerStatus, state: TellerUiState, actions: TellerActions, modifier: Modifier = Modifier) {
    val colors = CentsibleTheme.colors
    val steps = TellerStep.entries
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
                TellerStep.Account -> AccountStep()
                TellerStep.ApplicationId -> ApplicationIdStep(state, actions)
                TellerStep.Certificate -> CertificateStep(status, state, actions)
                TellerStep.ConnectBank -> ConnectStep()
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val back = steps.getOrNull(index - 1)?.takeIf { step != TellerStep.ConnectBank || !status.configured }
            if (back != null) OutlinedButton(onClick = { actions.wizardTo(back) }, modifier = Modifier.weight(1f)) { Text("Back") }
            when (step) {
                TellerStep.Account -> Button(onClick = { actions.wizardTo(TellerStep.ApplicationId) }, modifier = Modifier.weight(1f)) { Text("I have an account") }
                TellerStep.ApplicationId -> Button(
                    onClick = { actions.wizardTo(TellerStep.Certificate) },
                    enabled = APP_ID.matches(state.form.applicationId.trim()),
                    modifier = Modifier.weight(1f),
                ) { Text("Next") }
                TellerStep.Certificate -> {
                    val ready = state.form.environment == "sandbox" || (state.form.certificate.isNotBlank() && state.form.privateKey.isNotBlank()) || status.hasCertificate
                    Button(onClick = actions.saveFromWizard, enabled = ready && !state.busy, modifier = Modifier.weight(1f)) { Text(if (state.busy) "Saving…" else "Save to my bridge") }
                }
                TellerStep.ConnectBank -> {
                    OutlinedButton(onClick = { actions.wizardTo(null) }, modifier = Modifier.weight(1f)) { Text("Later") }
                    Button(onClick = { actions.connect(null) }, enabled = state.canEdit && !state.busy, modifier = Modifier.weight(1f)) { Text("Connect a bank") }
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
        "Make a free Teller account",
        "Teller connects to your bank for Centsible, as a second option beside SimpleFIN. For a household it's free: Teller's development plan covers up to 100 bank logins with real banks.",
    )
    CentsibleCard {
        Text("You'll need about 5 minutes. A computer is easiest for the Teller dashboard, but a phone works.", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        Hint(1, "Open teller.io and sign up.")
        Hint(2, "Confirm your email, then sign in to the dashboard.")
        Hint(3, "Come back here when you see your application.")
    }
    Button(onClick = { uri.openUri("https://teller.io") }, modifier = Modifier.fillMaxWidth()) { Text("Open teller.io") }
}

@Composable
private fun ApplicationIdStep(state: TellerUiState, actions: TellerActions) {
    val colors = CentsibleTheme.colors
    StepTitle("Copy your Application ID", "It tells Teller which app is asking. It isn't secret, so it's fine to paste it here.")
    CentsibleCard {
        Hint(1, "In the Teller dashboard, open Application.")
        Hint(2, "Copy the Application ID. It starts with app_.")
        Hint(3, "Paste it below.")
    }
    val id = state.form.applicationId
    OutlinedTextField(
        id, { actions.onForm(state.form.copy(applicationId = it.trim())) },
        label = { Text("Application ID") },
        placeholder = { Text("app_…") },
        singleLine = true,
        isError = id.isNotBlank() && !APP_ID.matches(id.trim()),
        supportingText = { if (id.isNotBlank() && !APP_ID.matches(id.trim())) Text("It should look like app_ followed by letters and numbers.", color = colors.negative) },
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun CertificateStep(status: TellerStatus, state: TellerUiState, actions: TellerActions) {
    val colors = CentsibleTheme.colors
    val context = LocalContext.current
    var paste by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(state.form.environment != "development") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) actions.certificateFiles(uris.mapNotNull { readSmallFile(context, it) })
    }
    StepTitle(
        "Add your certificate",
        "Teller only answers requests signed with your own certificate. It's stored on your bridge, in a file only the bridge can read, and never leaves it.",
    )
    CentsibleCard {
        Hint(1, "In the Teller dashboard, open Certificates and choose Create certificate.")
        Hint(2, "Teller downloads your certificate and private key (a .zip, or two .pem files). Keep them private.")
        Hint(3, "Choose them below. Pick the .zip, or both .pem files at once.")
    }
    Button(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) { Text("Choose the downloaded files") }
    state.certificateNote?.let { note ->
        val ok = note.startsWith("Certificate ✓")
        Text(note, style = MaterialTheme.typography.bodyMedium, color = if (ok) colors.positive else colors.negative)
    }
    if (status.hasCertificate && state.certificateNote == null) {
        Text("Your bridge already has a certificate. Choose files only to replace it.", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
    }
    TextButton(onClick = { paste = !paste }) { Text(if (paste) "Hide the paste boxes" else "Paste the text instead") }
    if (paste) {
        OutlinedTextField(
            state.form.certificate, { actions.onForm(state.form.copy(certificate = it)) },
            label = { Text("Certificate (certificate.pem)") }, minLines = 3, maxLines = 6,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            state.form.privateKey, { actions.onForm(state.form.copy(privateKey = it)) },
            label = { Text("Private key (private_key.pem)") }, minLines = 3, maxLines = 6,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide environment" else "Environment: ${state.form.environment.replaceFirstChar { it.uppercase() }}") }
    if (advanced) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TellerStatus.ENVIRONMENTS.forEach { env ->
                FilterChip(selected = state.form.environment == env, onClick = { actions.onForm(state.form.copy(environment = env)) }, label = { Text(env.replaceFirstChar { it.uppercase() }) })
            }
        }
        Text(TellerStatus.environmentLabel(state.form.environment), style = MaterialTheme.typography.bodySmall, color = colors.textTertiary)
    }
}

@Composable
private fun ConnectStep() {
    StepTitle(
        "Connect your first bank",
        "Teller opens its own sign-in page for your bank. Centsible never sees your bank password, only the access Teller grants.",
    )
    CentsibleCard {
        Hint(1, "Tap Connect a bank and find your bank (Wells Fargo, Capital One, …).")
        Hint(2, "Sign in. Some banks open their own page and ask for a code.")
        Hint(3, "Back here, tap each account to link it to an account in your budget, and check the preview: purchases should be negative.")
    }
    Text(
        "Accounts that sync through SimpleFIN now move over to Teller when you link them. What's already imported is matched, not duplicated.",
        style = MaterialTheme.typography.bodySmall,
        color = CentsibleTheme.colors.textSecondary,
    )
}

/** Reads a picked file (name and bytes), refusing anything over 2 MB: certificates are tiny. */
private fun readSmallFile(context: Context, uri: Uri): Pair<String, ByteArray>? = runCatching {
    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: uri.lastPathSegment.orEmpty()
    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > 2_000_000) return@runCatching null
            out.write(buf, 0, n)
        }
        out.toByteArray()
    } ?: return@runCatching null
    name to bytes
}.getOrNull()
