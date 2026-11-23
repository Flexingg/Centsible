package app.centsible.feature.settings

import app.centsible.core.model.AppRelease
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdaterTest {
    @Test
    fun `parses versionCode from CI tag format`() {
        assertEquals(127, AppUpdater.parseVersionCode("v0.2.127"))
        assertEquals(128, AppUpdater.parseVersionCode("0.2.128"))
        assertEquals(0, AppUpdater.parseVersionCode("0.3.0"))
    }

    @Test
    fun `detects newer release by semver and versionCode`() {
        val r128 = AppRelease("0.2.128", 128, null, "https://example.com", "https://example.com/app.apk", "Fixes")
        assertTrue(AppUpdater.isUpdateAvailable(r128, installedCode = 127, installedName = "0.2.127"))
        assertFalse(AppUpdater.isUpdateAvailable(r128, installedCode = 128, installedName = "0.2.128"))
        assertFalse(AppUpdater.isUpdateAvailable(r128, installedCode = 129, installedName = "0.2.129"))

        val majorBump = AppRelease("0.3.0", 0, null, "https://example.com", "https://example.com/app.apk", null)
        assertTrue(AppUpdater.isUpdateAvailable(majorBump, installedCode = 128, installedName = "0.2.128"))

        val noApk = r128.copy(apkUrl = null)
        assertFalse(AppUpdater.isUpdateAvailable(noApk, installedCode = 127, installedName = "0.2.127"))
    }
}
