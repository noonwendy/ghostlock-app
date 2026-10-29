package com.ghostlock.app.data.profile

/**
 * Which execution implementation a resolved profile dispatches to.
 *
 * The selector is Kotlin-only: `NativeProfileDocument` never reads it, so the
 * GLK1 bytes of a native profile are unchanged by its presence. `native` is the
 * default, so every profile written before this field existed keeps its exact
 * meaning. An unknown token is an error, never a fallback: a typo must not
 * silently run another engine ([ProfileResolver.validateMerged] reports it).
 */
enum class ExecutionEngine(val token: String) {
    /** The upstream GLK1 profile + `libghostlock.so` pipeline. */
    NATIVE("native"),

    /**
     * The HONOR 80 GT specialized chain: a per-firmware exploit plus the
     * custom KernelSU loader, both carried by the APK and supervised by the
     * Shizuku UserService. It consumes no GLK1 document.
     */
    H80GT("h80gt"),
    ;

    val isNative: Boolean get() = this == NATIVE

    companion object {
        /** Profile key holding the selector. */
        const val Key = "engine"

        /** Profile section carrying the H80GT target and payload id. */
        const val Section = "h80gt"

        /** Null when the token is not one of the declared engines. */
        fun fromToken(token: String?): ExecutionEngine? = when {
            token.isNullOrBlank() -> NATIVE
            else -> entries.firstOrNull { it.token == token }
        }
    }
}

/**
 * The `h80gt` profile section: the exact target the specialized chain was
 * compiled for plus the payload id the APK must carry.
 *
 * `release` is the profile's own top-level `uname -r` value and stays the only
 * hard key — that is exactly how GhostLock-H80GT selects between firmwares.
 * [device] and [fingerprint] are optional refinements: when a profile declares
 * them they are compared and a mismatch refuses the run; when it does not, the
 * kernel string alone decides. That keeps one profile per firmware possible
 * without inventing data for firmwares nobody has recorded.
 */
data class H80gtSpec(
    val release: String,
    val payload: String,
    val firmware: String,
    val managerVersion: String,
    val device: String? = null,
    val fingerprint: String? = null,
) {
    companion object {
        /** Always required, at least one character. */
        val RequiredFields = listOf("payload", "firmware", "manager_version")

        /** Compared only when present. */
        val OptionalFields = listOf("device", "fingerprint")

        /** Parses the section, returning one error per missing or blank required field. */
        fun parse(profile: Map<String, Any?>): Pair<H80gtSpec?, List<ConfigError>> {
            val raw = profile[ExecutionEngine.Section]
            if (raw !is Map<*, *>) {
                return null to listOf(
                    ConfigError(ExecutionEngine.Section, "missing or not an object"),
                )
            }
            fun text(field: String): String? =
                (raw[field] as? String)?.trim()?.takeIf { it.isNotEmpty() }

            val errors = mutableListOf<ConfigError>()
            for (field in RequiredFields) {
                if (text(field) == null) {
                    errors += ConfigError("${ExecutionEngine.Section}.$field", "missing or empty")
                }
            }
            val release = (profile["release"] as? String)?.trim().orEmpty()
            if (release.isEmpty()) errors += ConfigError("release", "missing")
            if (errors.isNotEmpty()) return null to errors
            return H80gtSpec(
                release = release,
                payload = text("payload")!!,
                firmware = text("firmware")!!,
                managerVersion = text("manager_version")!!,
                device = text("device"),
                fingerprint = text("fingerprint"),
            ) to emptyList()
        }
    }
}
