package com.ghostlock.app.data.h80gt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The module is built from KernelSU v3.3.0 and the manager version-checks the
 * kernel side, so the handoff only opens a manager whose version matches. A
 * mismatch is reported with both versions, never silently swapped.
 */
class H80gtManagerTest {
    private fun payload() = H80gtPayload(
        id = "annap-AGT-AN00_9.0.0.230",
        device = "AGT-AN00",
        release = "5.10.236-android12-9-gd671cf8c2b02",
        fingerprint = "f",
        firmware = "9.0.0.230",
        status = "verified",
        source = "test",
        bundleSha256 = "0".repeat(64),
        managerPackage = "me.weishu.kernelsu",
        managerVersion = "3.3.0",
        artifacts = emptyList(),
    )

    @Test
    fun `version normalization keeps the marketing version`() {
        assertEquals("3.3.0", H80gtManager.normalizeVersion("v3.3.0"))
        assertEquals("3.3.1", H80gtManager.normalizeVersion("v3.3.1 (32680-5)"))
        assertEquals("3.3", H80gtManager.normalizeVersion("3.3"))
        assertNull(H80gtManager.normalizeVersion(""))
        assertNull(H80gtManager.normalizeVersion(null))
        assertNull(H80gtManager.normalizeVersion("nightly"))
    }

    @Test
    fun `a missing segment counts as zero`() {
        assertTrue(H80gtManager.matches("3.3", "3.3.0"))
        assertTrue(H80gtManager.matches("v3.3.0", "3.3.0"))
        assertTrue(H80gtManager.matches("v3.3.0 (30000-1)", "3.3.0"))
        assertFalse(H80gtManager.matches("v3.3.1", "3.3.0"))
        assertFalse(H80gtManager.matches(null, "3.3.0"))
    }

    @Test
    fun `the selected package wins over the payload default`() {
        assertEquals("custom.pkg", H80gtManager.resolvePackage("custom.pkg", payload()))
        assertEquals("me.weishu.kernelsu", H80gtManager.resolvePackage(null, payload()))
        assertEquals("me.weishu.kernelsu", H80gtManager.resolvePackage("  ", payload()))
    }

    @Test
    fun `a matching manager opens`() {
        val decision = H80gtManager.decide(
            null, payload(), H80gtManager.Candidate("me.weishu.kernelsu", "v3.3.0"),
        )
        assertEquals(H80gtManager.Decision.Open("me.weishu.kernelsu"), decision)
    }

    @Test
    fun `a missing manager blocks with its package name`() {
        val decision = H80gtManager.decide(null, payload(), null)
        val blocked = decision as H80gtManager.Decision.Blocked
        assertEquals(H80gtIssueCode.MANAGER_MISSING, blocked.issue.code)
        assertTrue(blocked.issue.detail.contains("me.weishu.kernelsu"))
    }

    @Test
    fun `a version mismatch blocks and names both versions`() {
        val decision = H80gtManager.decide(
            null, payload(), H80gtManager.Candidate("me.weishu.kernelsu", "v3.3.1 (32680-5)"),
        )
        val blocked = decision as H80gtManager.Decision.Blocked
        assertEquals(H80gtIssueCode.MANAGER_VERSION_MISMATCH, blocked.issue.code)
        assertTrue(blocked.issue.detail.contains("3.3.1"))
        assertTrue(blocked.issue.detail.contains("3.3.0"))
    }

    @Test
    fun `a candidate for another package does not satisfy the resolved one`() {
        val blocked = H80gtManager.decide(
            "me.weishu.kernelsu", payload(),
            H80gtManager.Candidate("com.resukisu.resukisu", "v3.3.0"),
        ) as H80gtManager.Decision.Blocked
        assertEquals(H80gtIssueCode.MANAGER_MISSING, blocked.issue.code)
    }
}
