package app.centsible

import android.app.Activity
import android.app.KeyguardManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.centsible.core.designsystem.theme.CentsibleTheme

/**
 * Asks for the fingerprint/face/PIN with the platform prompt (no extra libraries).
 * Android 11+: biometrics or the device PIN in one prompt. Android 9–10: biometrics
 * with a "Use PIN" button. Android 8: the system's confirm-credential screen.
 */
internal object DeviceUnlock {
    fun isAvailable(activity: Activity): Boolean =
        activity.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    fun prompt(activity: Activity, confirmCredential: ActivityResultLauncher<android.content.Intent>, onUnlocked: () -> Unit) {
        val executor = activity.mainExecutor
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onUnlocked()
        }
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> BiometricPrompt.Builder(activity)
                .setTitle("Unlock Centsible")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build()
                .authenticate(CancellationSignal(), executor, callback)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> BiometricPrompt.Builder(activity)
                .setTitle("Unlock Centsible")
                .setDeviceCredentialAllowed(true)
                .build()
                .authenticate(CancellationSignal(), executor, callback)
            else -> {
                @Suppress("DEPRECATION")
                val intent = activity.getSystemService(KeyguardManager::class.java)
                    ?.createConfirmDeviceCredentialIntent("Unlock Centsible", null)
                if (intent == null) onUnlocked() else confirmCredential.launch(intent)
            }
        }
    }
}

/** Shown instead of the app while locked; nothing behind it is composed. */
@Composable
internal fun LockScreen(onUnlock: () -> Unit) {
    // Ask straight away; the button is for when the prompt was dismissed.
    LaunchedEffect(Unit) { onUnlock() }
    CentsibleTheme {
        Surface(Modifier.fillMaxSize(), color = CentsibleTheme.colors.canvas) {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("🔒", style = MaterialTheme.typography.displayMedium)
                Spacer(Modifier.height(16.dp))
                Text("Centsible is locked", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(24.dp))
                Button(onClick = onUnlock) { Text("Unlock") }
            }
        }
    }
}

@Composable
internal fun BlankScreen() {
    CentsibleTheme { Surface(Modifier.fillMaxSize(), color = CentsibleTheme.colors.canvas) {} }
}
