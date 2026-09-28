package app.centsible.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.centsible.core.designsystem.component.CentsibleCard
import app.centsible.core.designsystem.theme.CentsibleTheme
import app.centsible.core.model.ActualSetup
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

@Composable
fun PairingRoute(deepLink: String?, viewModel: OnboardingViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(deepLink) { deepLink?.let(viewModel::onScanned) }
    OnboardingScreen(
        state = state,
        actions = OnboardingActions(
            edit = viewModel::edit,
            next = viewModel::next,
            back = viewModel::back,
            claim = viewModel::claim,
            join = viewModel::join,
            scan = {
                // Google's code scanner: no camera permission needed, the UI runs in Play services.
                val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
                GmsBarcodeScanning.getClient(context, options).startScan()
                    .addOnSuccessListener { it.rawValue?.let(viewModel::onScanned) }
            },
        ),
    )
}

data class OnboardingActions(
    val edit: ((OnboardingUiState) -> OnboardingUiState) -> Unit = {},
    val next: () -> Unit = {},
    val back: () -> Unit = {},
    val claim: () -> Unit = {},
    val join: () -> Unit = {},
    val scan: () -> Unit = {},
)

@Composable
fun OnboardingScreen(state: OnboardingUiState, actions: OnboardingActions) {
    val colors = CentsibleTheme.colors
    Column(
        Modifier.fillMaxSize().background(colors.canvas).statusBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (state.step) {
            OnboardingStep.Address -> AddressStep(state, actions)
            OnboardingStep.Setup -> SetupStep(state, actions)
            OnboardingStep.Join -> JoinStep(state, actions)
        }
        state.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.negative) }
        Text(
            "Centsible talks only to your own bridge. Nothing is sent anywhere else.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textTertiary,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

@Composable
private fun AddressStep(state: OnboardingUiState, actions: OnboardingActions) {
    val colors = CentsibleTheme.colors
    Spacer(Modifier.height(24.dp))
    Text("💰", style = MaterialTheme.typography.displaySmall)
    Text("Centsible", style = MaterialTheme.typography.headlineMedium)
    Text("Your household's Actual budget, on your phone.", style = MaterialTheme.typography.bodyLarge, color = colors.textSecondary)

    CentsibleCard {
        Text("Where's your bridge?", style = MaterialTheme.typography.titleMedium)
        Text(
            "The address you gave the bridge in Cloudflare. Setting it up for the first time, or joining someone's? The app works out which.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        OutlinedTextField(
            state.url, { v -> actions.edit { it.copy(url = v) } },
            label = { Text("Bridge address") },
            placeholder = { Text("budget-api.example.com") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { actions.next() }),
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.showAccess) {
            Spacer(Modifier.height(12.dp))
            Text("Cloudflare Access service token", style = MaterialTheme.typography.labelLarge)
            Text(
                "From Zero Trust → Access → Service Auth. Leave empty if the bridge isn't behind Access.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
            OutlinedTextField(state.cfId, { v -> actions.edit { it.copy(cfId = v) } }, label = { Text("Client ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                state.cfSecret, { v -> actions.edit { it.copy(cfSecret = v) } },
                label = { Text("Client secret") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            TextButton(onClick = { actions.edit { it.copy(showAccess = true) } }) { Text("Behind Cloudflare Access?") }
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = actions.next, enabled = state.canContinue, modifier = Modifier.fillMaxWidth()) {
            if (state.busy) app.centsible.core.designsystem.component.DialSpinner(size = 20.dp, track = colors.card.copy(alpha = 0.3f)) else Text("Continue")
        }
    }

    CentsibleCard {
        Text("Got an invite?", style = MaterialTheme.typography.titleMedium)
        Text(
            "Scan the QR code from the person who set up the bridge. It fills everything in.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        OutlinedButton(onClick = actions.scan, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.QrCodeScanner, contentDescription = null)
            Text("  Scan invite QR code")
        }
    }
}

@Composable
private fun StepHeader(title: String, subtitle: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
        Column {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = CentsibleTheme.colors.textSecondary)
        }
    }
}

@Composable
private fun SetupStep(state: OnboardingUiState, actions: OnboardingActions) {
    val colors = CentsibleTheme.colors
    StepHeader("Set up your bridge", state.address?.url.orEmpty(), actions.back)

    CentsibleCard {
        Text("1. Setup code", style = MaterialTheme.typography.titleMedium)
        Text(
            "The bridge prints it when it starts. On your server run:",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            "docker compose logs bridge",
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.padding(vertical = 6.dp),
        )
        Text("Or use BRIDGE_SETUP_CODE if you set one in .env.", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            state.setupCode, { v -> actions.edit { it.copy(setupCode = v.uppercase()) } },
            label = { Text("Setup code") },
            placeholder = { Text("ABCD-EFGH") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            modifier = Modifier.fillMaxWidth(),
        )
    }

    CentsibleCard {
        Text("2. You", style = MaterialTheme.typography.titleMedium)
        Text("You'll be the household owner, and can invite everyone else.", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            state.displayName, { v -> actions.edit { it.copy(displayName = v) } },
            label = { Text("Your name") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(state.deviceName, { v -> actions.edit { it.copy(deviceName = v) } }, label = { Text("This phone's name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    }

    ActualCard(state, actions)

    Button(onClick = actions.claim, enabled = state.canClaim, modifier = Modifier.fillMaxWidth()) {
        if (state.busy) app.centsible.core.designsystem.component.DialSpinner(size = 20.dp, track = colors.card.copy(alpha = 0.3f)) else Text("Set up")
    }
}

@Composable
private fun ActualCard(state: OnboardingUiState, actions: OnboardingActions) {
    val colors = CentsibleTheme.colors
    when (state.actual) {
        ActualSetup.Ready, ActualSetup.Unknown -> Unit
        ActualSetup.NeedsPassword, ActualSetup.NeedsLogin -> CentsibleCard {
            val new = state.actual == ActualSetup.NeedsPassword
            Text("3. Actual password", style = MaterialTheme.typography.titleMedium)
            Text(
                if (new) "Your Actual server is brand new. Choose its password: you'll also use it to sign in to Actual on the web."
                else "The password you use to sign in to Actual on the web. The bridge keeps it on your server, so you won't need it again.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                state.actualPassword, { v -> actions.edit { it.copy(actualPassword = v) } },
                label = { Text(if (new) "New password" else "Actual password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            if (new) {
                OutlinedTextField(
                    state.actualPasswordAgain, { v -> actions.edit { it.copy(actualPasswordAgain = v) } },
                    label = { Text("Same password again") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            state.passwordProblem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.negative) }
        }
        ActualSetup.Unreachable -> CentsibleCard {
            Text("The bridge can't reach Actual", style = MaterialTheme.typography.titleMedium, color = colors.negative)
            Text(
                "Check that Actual is running and that ACTUAL_SERVER_URL in the bridge's .env points at it (in the included docker compose file: http://actual:5006). Then try again.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
            TextButton(onClick = actions.next) { Text("Try again") }
        }
        ActualSetup.Unsupported -> CentsibleCard {
            Text("Actual signs in with OpenID", style = MaterialTheme.typography.titleMedium)
            Text(
                "The app can't enter an OpenID login for the bridge. Set ACTUAL_SESSION_TOKEN in the bridge's .env, restart it, then try again.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
            TextButton(onClick = actions.next) { Text("Try again") }
        }
    }
}

@Composable
private fun JoinStep(state: OnboardingUiState, actions: OnboardingActions) {
    val colors = CentsibleTheme.colors
    StepHeader("Join your household", state.address?.url.orEmpty(), actions.back)
    CentsibleCard {
        Text("Invite code", style = MaterialTheme.typography.titleMedium)
        Text(
            "Ask the owner to open More → Settings → Household and tap Invite next to your name. Scan their QR code, or type the code under it.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        OutlinedButton(onClick = actions.scan, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.QrCodeScanner, contentDescription = null)
            Text("  Scan invite QR code")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            state.inviteCode, { v -> actions.edit { it.copy(inviteCode = v.uppercase()) } },
            label = { Text("Invite code") },
            placeholder = { Text("ABCD-EFGH") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(state.deviceName, { v -> actions.edit { it.copy(deviceName = v) } }, label = { Text("This phone's name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Button(onClick = actions.join, enabled = state.canJoin, modifier = Modifier.fillMaxWidth()) {
            if (state.busy) app.centsible.core.designsystem.component.DialSpinner(size = 20.dp, track = colors.card.copy(alpha = 0.3f)) else Text("Join")
        }
    }
}
