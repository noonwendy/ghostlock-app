package com.ghostlock.app.data.h80gt

import com.ghostlock.app.data.profile.H80gtSpec

/**
 * A single reason a run is refused or flagged. The UI maps [code] to a
 * localized message; [detail] names the mismatching values for the log and for
 * the user, so a refusal always says *what* did not match.
 */
enum class H80gtIssueCode {
    ENGINE_UNKNOWN,
    PROFILE_INVALID,
    PAYLOAD_MISSING,
    PAYLOAD_INCOMPLETE,
    PAYLOAD_TARGET_MISMATCH,
    DEVICE_MISMATCH,
    RELEASE_MISMATCH,
    FINGERPRINT_MISMATCH,
    MANAGER_MISSING,
    MANAGER_VERSION_MISMATCH,
    SHIZUKU_NOT_READY,
    MODULE_ALREADY_LOADED,
    CHAIN_ALIVE,
    SELINUX_NOT_ENFORCING,
    PREVIOUS_RUN_NEEDS_REBOOT,
    LOW_STORAGE,
}

data class H80gtIssue(val code: H80gtIssueCode, val detail: String = "") {
    override fun toString(): String = if (detail.isEmpty()) code.name else "${code.name}: $detail"
}

/** Blockers stop the run; warnings are recorded and shown but do not stop it. */
data class H80gtVerdict(
    val blockers: List<H80gtIssue> = emptyList(),
    val warnings: List<H80gtIssue> = emptyList(),
) {
    val ok: Boolean get() = blockers.isEmpty()

    fun describe(): String =
        (blockers + warnings).joinToString("; ") { it.toString() }.ifEmpty { "ok" }

    companion object {
        val OK = H80gtVerdict()
    }
}

/** What the device claims to be; read from `Build` on either side of the binder. */
data class H80gtDeviceFacts(val model: String, val fingerprint: String, val release: String)

/** Device state only the shell identity can read. */
data class H80gtShellFacts(
    val moduleLoaded: Boolean = false,
    val chainAlive: Boolean = false,
    /** null when the state could not be read at all. */
    val enforcing: Boolean? = null,
    val freeBytes: Long = Long.MAX_VALUE,
)

/**
 * Pure preflight decisions, run twice: once in the app before it binds
 * Shizuku (so a refusal costs nothing) and once in the UserService before it
 * stages anything (the shell side is authoritative for device state).
 *
 * These functions perform no IO, so every refusal path is unit-tested.
 */
object H80gtPreflight {
    /** The payload plus a working directory need roughly this much room. */
    const val MinimumFreeBytes = 64L * 1024 * 1024

    /**
     * App-side check. [payload] is null when the profile names an id the APK
     * does not carry; [manager] is null when no manager package is installed.
     */
    fun appSide(
        spec: H80gtSpec,
        payload: H80gtPayload?,
        device: H80gtDeviceFacts,
        manager: H80gtManager.Candidate?,
        previousRunNeedsReboot: Boolean,
        shizukuReady: Boolean,
    ): H80gtVerdict {
        val blockers = mutableListOf<H80gtIssue>()
        val warnings = mutableListOf<H80gtIssue>()

        if (!shizukuReady) blockers += H80gtIssue(H80gtIssueCode.SHIZUKU_NOT_READY)
        if (previousRunNeedsReboot) {
            blockers += H80gtIssue(H80gtIssueCode.PREVIOUS_RUN_NEEDS_REBOOT)
        }
        if (payload == null) {
            blockers += H80gtIssue(H80gtIssueCode.PAYLOAD_MISSING, spec.payload)
            return H80gtVerdict(blockers, warnings)
        }
        blockers += targetIssues(spec, payload, device)
        val missing = payload.missingRoles()
        if (missing.isNotEmpty()) {
            blockers += H80gtIssue(H80gtIssueCode.PAYLOAD_INCOMPLETE, missing.joinToString(","))
        }
        if (manager == null) {
            blockers += H80gtIssue(H80gtIssueCode.MANAGER_MISSING, payload.managerPackage)
        } else if (!H80gtManager.matches(manager.versionName, payload.managerVersion)) {
            /* The run itself is still useful (the module is independent of the
             * manager UI); only the automatic handoff is blocked later. */
            warnings += H80gtIssue(
                H80gtIssueCode.MANAGER_VERSION_MISMATCH,
                "${manager.packageName} ${manager.versionName ?: "?"} != ${payload.managerVersion}",
            )
        }
        return H80gtVerdict(blockers, warnings)
    }

    /** Shell-side check, run after binding and before staging. */
    fun shellSide(
        spec: H80gtSpec,
        payload: H80gtPayload,
        device: H80gtDeviceFacts,
        shell: H80gtShellFacts,
    ): H80gtVerdict {
        val blockers = mutableListOf<H80gtIssue>()
        blockers += targetIssues(spec, payload, device)
        if (shell.moduleLoaded) {
            blockers += H80gtIssue(H80gtIssueCode.MODULE_ALREADY_LOADED)
        }
        if (shell.chainAlive) {
            blockers += H80gtIssue(H80gtIssueCode.CHAIN_ALIVE)
        }
        if (shell.enforcing == false) {
            blockers += H80gtIssue(H80gtIssueCode.SELINUX_NOT_ENFORCING)
        }
        if (shell.freeBytes < MinimumFreeBytes) {
            blockers += H80gtIssue(H80gtIssueCode.LOW_STORAGE, shell.freeBytes.toString())
        }
        return H80gtVerdict(blockers)
    }

    /**
     * The target bindings. `release` (the kernel string) and `firmware` are the
     * hard keys — that is how GhostLock-H80GT itself selects a firmware. The
     * model and the fingerprint are optional refinements: they are compared
     * only on the side that declares them, so a firmware without recorded
     * metadata still runs while a declared value is still enforced.
     */
    private fun targetIssues(
        spec: H80gtSpec,
        payload: H80gtPayload,
        device: H80gtDeviceFacts,
    ): List<H80gtIssue> {
        val issues = mutableListOf<H80gtIssue>()
        if (payload.release != spec.release || payload.firmware != spec.firmware) {
            issues += H80gtIssue(
                H80gtIssueCode.PAYLOAD_TARGET_MISMATCH,
                "payload=${payload.firmware}/${payload.release}",
            )
        }
        if (spec.device != null && payload.device != null && spec.device != payload.device) {
            issues += H80gtIssue(
                H80gtIssueCode.PAYLOAD_TARGET_MISMATCH,
                "device profile=${spec.device} payload=${payload.device}",
            )
        }
        if (spec.fingerprint != null && payload.fingerprint != null &&
            spec.fingerprint != payload.fingerprint
        ) {
            issues += H80gtIssue(
                H80gtIssueCode.PAYLOAD_TARGET_MISMATCH,
                "fingerprint differs between profile and payload",
            )
        }
        if (device.release != spec.release) {
            issues += H80gtIssue(H80gtIssueCode.RELEASE_MISMATCH, "${device.release} != ${spec.release}")
        }
        val expectedDevice = spec.device ?: payload.device
        if (expectedDevice != null && device.model != expectedDevice) {
            issues += H80gtIssue(H80gtIssueCode.DEVICE_MISMATCH, "${device.model} != $expectedDevice")
        }
        val expectedFingerprint = spec.fingerprint ?: payload.fingerprint
        if (expectedFingerprint != null && device.fingerprint != expectedFingerprint) {
            issues += H80gtIssue(
                H80gtIssueCode.FINGERPRINT_MISMATCH,
                device.fingerprint.take(96),
            )
        }
        return issues
    }
}
