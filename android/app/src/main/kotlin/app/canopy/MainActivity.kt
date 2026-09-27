package app.canopy

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private var pairingLink by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pairingLink = intent?.pairingLink()
        setContent { CanopyApp(pairingLink = pairingLink) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.pairingLink()?.let { pairingLink = it }
    }

    private fun Intent.pairingLink(): String? = data?.takeIf { it.scheme == "actualbridge" }?.toString()
}
