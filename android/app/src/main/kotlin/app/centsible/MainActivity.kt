package app.centsible

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import app.centsible.core.domain.AppLockPolicy
import app.centsible.core.domain.AppLockSettings
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var appLock: AppLockSettings

    private var pairingLink by mutableStateOf<String?>(null)
    /** null until the setting is read: show nothing rather than flash balances or a prompt. */
    private var locked by mutableStateOf<Boolean?>(null)
    private var lockEnabled = false
    private var backgroundedAt: Long? = null

    private val confirmCredential = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) locked = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pairingLink = intent?.pairingLink()
        lifecycleScope.launch {
            locked = AppLockPolicy.shouldLock(appLock.enabled.first(), backgroundedAt = null, now = SystemClock.elapsedRealtime())
            appLock.enabled.collect { enabled ->
                lockEnabled = enabled
                // Keep balances out of screenshots and the recent-apps preview.
                if (enabled) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                if (!enabled) locked = false
            }
        }
        setContent {
            when (locked) {
                null -> BlankScreen()
                true -> LockScreen(onUnlock = { DeviceUnlock.prompt(this, confirmCredential) { locked = false } })
                false -> CentsibleApp(pairingLink = pairingLink)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (backgroundedAt != null && AppLockPolicy.shouldLock(lockEnabled, backgroundedAt, SystemClock.elapsedRealtime())) locked = true
        backgroundedAt = null
    }

    override fun onStop() {
        super.onStop()
        // Not while the unlock prompt itself (or the credential screen) covers us.
        if (locked == false) backgroundedAt = SystemClock.elapsedRealtime()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.pairingLink()?.let { pairingLink = it }
    }

    private fun Intent.pairingLink(): String? = data?.takeIf { it.scheme == "actualbridge" }?.toString()
}
