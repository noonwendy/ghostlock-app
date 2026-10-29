package com.ghostlock.app.data.h80gt

import com.ghostlock.app.data.profile.H80gtSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every refusal path of the specialized engine, without a device.
 *
 * The kernel string is the only hard key (that is how GhostLock-H80GT selects a
 * firmware). The model and the fingerprint are optional refinements: declared
 * values are enforced, absent ones are not compared. The synthetic fingerprint
 * below is test data — no real build string is embedded anywhere.
 */
class H80gtPreflightTest {
    private val release = "5.10.236-android12-9-gd671cf8c2b02"
    private val sampleFingerprint = "HONOR/AGT-AN00/HNAGT:15/TESTBUILD:user/release-keys"

    /** Batch-1 style profile: both optional refinements declared. */
    private val strictSpec = H80gtSpec(
        release = release,
        payload = "annap-AGT-AN00_9.0.0.230",
        firmware = "9.0.0.230",
        managerVersion = "3.3.0",
        device = "AGT-AN00",
        fingerprint = sampleFingerprint,
    )

    /** Batch-2 style profile: selection by kernel string only. */
    private val relaxedSpec = H80gtSpec(
        release = release,
        payload = "annap-AGT-AN00_9.0.0.230",
        firmware = "9.0.0.230",
        managerVersion = "3.3.0",
    )

    private fun payload(
        release: String = this.release,
        firmware: String = "9.0.0.230",
        device: String? = null,
        fingerprint: String? = null,
        artifacts: List<H80gtArtifact> = fullArtifacts(),
    ) = H80gtPayload(
        id = "annap-AGT-AN00_9.0.0.230",
        release = release,
        firmware = firmware,
        status = "verified",
        source = "test",
        bundleSha256 = "0".repeat(64),
        managerPackage = "me.weishu.kernelsu",
        managerVersion = "3.3.0",
        artifacts = artifacts,
        device = device,
        fingerprint = fingerprint,
    )

    private fun fullArtifacts(): List<H80gtArtifact> = H80gtPayload.RequiredRoles.map { role ->
        H80gtArtifact(
            name = if (role == "loader_template") "ksu_loader.sh" else role,
            path = "h80gt/payload/test/$role",
            role = role,
            sha256 = "0".repeat(64),
            size = 1,
            executable = role != "module" && role != "policy_rules",
            render = role == "loader_template",
            license = "test",
            source = "test",
        )
    }

    private fun device(
        model: String = "AGT-AN00",
        fingerprint: String = sampleFingerprint,
        release: String = this.release,
    ) = H80gtDeviceFacts(model, fingerprint, release)

    private fun appSide(
        spec: H80gtSpec = strictSpec,
        payload: H80gtPayload? = payload(),
        device: H80gtDeviceFacts = device(),
        manager: H80gtManager.Candidate? = H80gtManager.Candidate("me.weishu.kernelsu", "v3.3.0"),
        needsReboot: Boolean = false,
        shizukuReady: Boolean = true,
    ) = H80gtPreflight.appSide(spec, payload, device, manager, needsReboot, shizukuReady)

    @Test
    fun `a matching device passes`() {
        val verdict = appSide()
        assertTrue(verdict.describe(), verdict.ok)
        assertTrue(verdict.warnings.isEmpty())
    }

    @Test
    fun `without the optional refinements any model and fingerprint pass`() {
        val verdict = appSide(
            spec = relaxedSpec,
            device = device(model = "PGT-AN10", fingerprint = "something/else"),
        )
        assertTrue(verdict.describe(), verdict.ok)
    }

    @Test
    fun `a declared model is still enforced`() {
        val verdict = appSide(device = device(model = "PGT-AN10"))
        assertFalse(verdict.ok)
        assertEquals(H80gtIssueCode.DEVICE_MISMATCH, verdict.blockers.first().code)
        assertTrue(verdict.blockers.first().detail.contains("AGT-AN00"))
    }

    @Test
    fun `a declared fingerprint is still enforced`() {
        val verdict = appSide(device = device(fingerprint = "another/build"))
        assertTrue(verdict.blockers.any { it.code == H80gtIssueCode.FINGERPRINT_MISMATCH })
    }

    @Test
    fun `a payload-side declaration is enforced even without a profile one`() {
        val verdict = appSide(
            spec = relaxedSpec,
            payload = payload(fingerprint = "payload/build"),
            device = device(fingerprint = "another/build"),
        )
        assertTrue(verdict.blockers.any { it.code == H80gtIssueCode.FINGERPRINT_MISMATCH })
    }

    @Test
    fun `another kernel is refused`() {
        val verdict = appSide(device = device(release = "5.10.209-android12-9-g516fa23f87fc"))
        assertTrue(verdict.blockers.any { it.code == H80gtIssueCode.RELEASE_MISMATCH })
    }

    @Test
    fun `a payload targeting another firmware is refused`() {
        val verdict = appSide(payload = payload(release = "5.10.168-android12-9-g804a21a522f0"))
        assertTrue(verdict.blockers.any { it.code == H80gtIssueCode.PAYLOAD_TARGET_MISMATCH })
    }

    @Test
    fun `a missing payload refuses`() {
        val verdict = appSide(payload = null)
        assertEquals(H80gtIssueCode.PAYLOAD_MISSING, verdict.blockers.first().code)
    }

    @Test
    fun `an incomplete payload refuses`() {
        val partial = payload(artifacts = fullArtifacts().filterNot { it.role == "userspace" })
        val verdict = appSide(payload = partial)
        assertEquals(H80gtIssueCode.PAYLOAD_INCOMPLETE, verdict.blockers.first().code)
        assertTrue(verdict.blockers.first().detail.contains("userspace"))
    }

    @Test
    fun `a missing manager refuses`() {
        val verdict = appSide(manager = null)
        assertEquals(H80gtIssueCode.MANAGER_MISSING, verdict.blockers.first().code)
    }

    @Test
    fun `a manager version mismatch only warns until the handoff`() {
        val verdict = appSide(manager = H80gtManager.Candidate("me.weishu.kernelsu", "v3.3.1"))
        assertTrue(verdict.ok)
        assertEquals(H80gtIssueCode.MANAGER_VERSION_MISMATCH, verdict.warnings.single().code)
    }

    @Test
    fun `shizuku and the previous reboot flag both refuse`() {
        val verdict = appSide(needsReboot = true, shizukuReady = false)
        val codes = verdict.blockers.map { it.code }.toSet()
        assertTrue(codes.contains(H80gtIssueCode.SHIZUKU_NOT_READY))
        assertTrue(codes.contains(H80gtIssueCode.PREVIOUS_RUN_NEEDS_REBOOT))
    }

    @Test
    fun `the shell side refuses a loaded module, a live chain and permissive selinux`() {
        val verdict = H80gtPreflight.shellSide(
            relaxedSpec,
            payload(),
            device(),
            H80gtShellFacts(
                moduleLoaded = true,
                chainAlive = true,
                enforcing = false,
                freeBytes = 0,
            ),
        )
        val codes = verdict.blockers.map { it.code }.toSet()
        assertEquals(
            setOf(
                H80gtIssueCode.MODULE_ALREADY_LOADED,
                H80gtIssueCode.CHAIN_ALIVE,
                H80gtIssueCode.SELINUX_NOT_ENFORCING,
                H80gtIssueCode.LOW_STORAGE,
            ),
            codes,
        )
    }

    @Test
    fun `the shell side accepts a clean device`() {
        val verdict = H80gtPreflight.shellSide(
            relaxedSpec,
            payload(),
            device(),
            H80gtShellFacts(
                moduleLoaded = false,
                chainAlive = false,
                enforcing = true,
                freeBytes = H80gtPreflight.MinimumFreeBytes,
            ),
        )
        assertTrue(verdict.describe(), verdict.ok)
    }
}
