package app.centsible.core.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockPolicyTest {
    @Test fun `off never locks`() = assertFalse(AppLockPolicy.shouldLock(false, null, 0))
    @Test fun `cold start locks`() = assertTrue(AppLockPolicy.shouldLock(true, null, 0))
    @Test fun `a quick trip away doesn't lock`() = assertFalse(AppLockPolicy.shouldLock(true, 1_000, 30_000))
    @Test fun `a minute away locks`() = assertTrue(AppLockPolicy.shouldLock(true, 1_000, 61_000))
}
