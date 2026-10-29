package com.ghostlock.app.data.h80gt

/**
 * Manager handoff policy.
 *
 * The bundled module is built from KernelSU `v3.3.0`, and the manager
 * version-checks the kernel side, so a different manager build can report a
 * loaded module as "not installed". The app therefore never silently opens a
 * manager it cannot vouch for: it either hands off to the pinned version or
 * reports why it cannot.
 */
object H80gtManager {
    /** Installed candidate: package name plus the version the package manager reports. */
    data class Candidate(val packageName: String, val versionName: String?)

    sealed interface Decision {
        data class Open(val packageName: String) : Decision

        data class Blocked(val issue: H80gtIssue) : Decision
    }

    /**
     * `"v3.3.1 (32680-5)"` -> `"3.3.1"`; null when nothing numeric remains.
     * Managers report the marketing version first, then a build suffix.
     */
    fun normalizeVersion(value: String?): String? {
        val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val token = raw.substringBefore(' ').trim().removePrefix("v").removePrefix("V")
        val numeric = token.takeWhile { it.isDigit() || it == '.' }.trimEnd('.')
        return numeric.takeIf { it.isNotEmpty() && it.any(Char::isDigit) }
    }

    /** Segment-wise compare; a missing segment counts as zero (`3.3` == `3.3.0`). */
    fun matches(installed: String?, expected: String): Boolean {
        val actual = normalizeVersion(installed)?.split('.')?.map { it.toIntOrNull() ?: return false }
            ?: return false
        val wanted = normalizeVersion(expected)?.split('.')?.map { it.toIntOrNull() ?: return false }
            ?: return false
        val size = maxOf(actual.size, wanted.size)
        for (index in 0 until size) {
            if (actual.getOrElse(index) { 0 } != wanted.getOrElse(index) { 0 }) return false
        }
        return true
    }

    /** User choice first, then the payload's own default. */
    fun resolvePackage(selected: String?, payload: H80gtPayload): String =
        selected?.takeIf { it.isNotBlank() } ?: payload.managerPackage

    /**
     * Decides the handoff. [installed] is the resolved package as the package
     * manager sees it; null means the package is absent, which is a hard block.
     * A version mismatch is a block that names both versions.
     */
    fun decide(
        selected: String?,
        payload: H80gtPayload,
        installed: Candidate?,
    ): Decision {
        val packageName = resolvePackage(selected, payload)
        if (installed == null || installed.packageName != packageName) {
            return Decision.Blocked(
                H80gtIssue(H80gtIssueCode.MANAGER_MISSING, packageName),
            )
        }
        val versionName = installed.versionName
        if (!matches(versionName, payload.managerVersion)) {
            return Decision.Blocked(
                H80gtIssue(
                    H80gtIssueCode.MANAGER_VERSION_MISMATCH,
                    "$packageName ${versionName ?: "?"} != ${payload.managerVersion}",
                ),
            )
        }
        return Decision.Open(packageName)
    }
}
