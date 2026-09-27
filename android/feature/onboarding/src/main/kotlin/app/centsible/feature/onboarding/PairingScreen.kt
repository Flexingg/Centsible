package app.centsible.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.centsible.core.designsystem.component.CentsibleCard
import app.centsible.core.designsystem.theme.CentsibleTheme
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

@Composable
fun PairingRoute(deepLink: String?, viewModel: PairingViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(deepLink) { deepLink?.let(viewModel::onScanned) }
    PairingScreen(
        state = state,
        onScan = {
            // Google's code scanner: no camera permission needed, the UI runs in Play services.
            val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
            GmsBarcodeScanning.getClient(context, options).startScan()
                .addOnSuccessListener { it.rawValue?.let(viewModel::onScanned) }
        },
        onUrl = viewModel::onUrl,
        onCode = viewModel::onCode,
        onDeviceName = viewModel::onDeviceName,
        onPair = viewModel::pair,
    )
}

@Composable
fun PairingScreen(
    state: PairingUiState,
    onScan: () -> Unit,
    onUrl: (String) -> Unit,
    onCode: (String) -> Unit,
    onDeviceName: (String) -> Unit,
    onPair: () -> Unit,
) {
    val colors = CentsibleTheme.colors
    Column(
        Modifier.fillMaxSize().background(colors.canvas).statusBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text("💰", style = MaterialTheme.typography.displaySmall)
        Text("Centsible", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Your household's Actual budget, on your phone. Connect to your bridge to get started.",
            style = MaterialTheme.typography.bodyLarge,
            color = colors.textSecondary,
        )
        CentsibleCard {
            Text("Scan to connect", style = MaterialTheme.typography.titleMedium)
            Text(
                "On your server run `admin add-member --pair`, or ask the household owner to invite you from Settings → Household.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            Button(onClick = onScan, enabled = !state.pairing, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.QrCodeScanner, contentDescription = null)
                Text("  Scan pairing QR code")
            }
        }
        CentsibleCard {
            Text("Or enter it manually", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                state.bridgeUrl, onUrl,
                label = { Text("Bridge URL") },
                placeholder = { Text("https://budget-api.example.com") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                state.code, onCode,
                label = { Text("Pairing code") },
                placeholder = { Text("ABCD-EFGH") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(state.deviceName, onDeviceName, label = { Text("This device's name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (state.cfId != null) {
                Text("🔒 Cloudflare Access token included", style = MaterialTheme.typography.labelSmall, color = colors.textSecondary, modifier = Modifier.padding(top = 8.dp))
            }
            state.error?.let {
                HorizontalDivider(Modifier.padding(vertical = 12.dp), color = colors.border)
                Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.negative)
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onPair, enabled = state.canSubmit, modifier = Modifier.fillMaxWidth()) {
                if (state.pairing) CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp) else Text("Connect")
            }
        }
        Text(
            "Centsible talks only to your own bridge. Nothing is sent anywhere else.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textTertiary,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}
