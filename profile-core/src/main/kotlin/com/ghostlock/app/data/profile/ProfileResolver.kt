package com.ghostlock.app.data.profile

import com.ghostlock.app.data.getLongAt
import com.ghostlock.app.data.route.RouteKind

/**
 * Single authority for mapping a merged profile value map onto typed core and
 * execution layers. It owns the route-aware field lookup shared by the app and
 * the Gradle exporter, and the fail-closed structural validation.
 */
object ProfileResolver {
    private val KnownTopLevel = setOf(
        "release", "schema_version", "kernel_major", "recommend_shizuku",
        "route", "fallback", "kernelsnitch", "task_struct", "cred", "offset",
        "kernel_phys_load", "kernel_phys_offset", "execution",
        ExecutionEngine.Key, ExecutionEngine.Section,
    )
    private val RequiredTopLevel = setOf(
        "release", "schema_version", "kernel_major", "route", "task_struct", "cred", "offset",
    )
    /** Keys that belong to the native wire document and must not appear next to an engine. */
    private val NativeOnlyTopLevel = listOf(
        "route", "fallback", "kernelsnitch", "task_struct", "cred", "offset",
        "kernel_phys_load", "kernel_phys_offset", "execution",
    )
    private val RequiredTaskStruct = listOf(
        "prio", "normal_prio", "sched_task_group", "pi_lock", "pi_waiters", "pi_top_task",
        "pi_blocked_on", "pid", "tgid", "atomic_flags", "real_cred", "cred", "comm", "tasks",
        "seccomp",
    )
    private val RequiredCred = listOf("copy_size", "caps_count")
    private val RequiredOffset = listOf(
        "init_task", "init_cred", "root_task_group", "selinux_enforcing",
    )

    /**
     * Canonical native field lookup over the declared route branches. The v2
     * transport carries `recommended_cpus`, so the effective `selected_cpus`
     * is folded into those slots; `compact_waiter`/`pselect_waiter_shift`/
     * `mcast.*` map onto `route.<name>.*` then `fallback.route.<name>.*`.
     */
    fun nativeValue(
        profile: Map<String, Any?>,
        route: String?,
        fallbackTo: String?,
        path: String,
    ): Long? {
        if (path == "execution.recommended_cpus.main" ||
            path == "execution.recommended_cpus.consumer"
        ) {
            val slot = path.removePrefix("execution.recommended_cpus.")
            profile.getLongAt("execution.selected_cpus.$slot")?.let { return it }
        }
        val branchField = when (path) {
            "compact_waiter" -> "compact_waiter"
            "pselect_waiter_shift" -> "waiter_shift"
            else -> null
        }
        if (branchField != null) {
            route?.let { name ->
                profile.getLongAt("route.$name.$branchField")?.let { return it }
            }
            if (fallbackTo != null && fallbackTo != "none") {
                profile.getLongAt("fallback.route.$fallbackTo.$branchField")?.let { return it }
            }
        }
        if (path.startsWith("mcast.")) {
            val field = path.removePrefix("mcast.")
            route?.let { name ->
                profile.getLongAt("route.$name.$field")?.let { return it }
            }
            if (fallbackTo != null && fallbackTo != "none") {
                profile.getLongAt("fallback.route.$fallbackTo.$field")?.let { return it }
            }
            profile.getLongAt("mcast.$field")?.let { return it }
        }
        return profile.getLongAt(path)
    }

    /**
     * The declared engine, or null when the value is present but not a known
     * token. An absent key means [ExecutionEngine.NATIVE].
     */
    fun executionEngine(profile: Map<String, Any?>): ExecutionEngine? =
        when (val raw = profile[ExecutionEngine.Key]) {
            null -> ExecutionEngine.NATIVE
            is String -> ExecutionEngine.fromToken(raw)
            else -> null
        }

    /** Tolerant read of the `h80gt` section; null when it is absent or incomplete. */
    fun h80gtSpec(profile: Map<String, Any?>): H80gtSpec? =
        H80gtSpec.parse(profile).first

    /** Fail-closed structural validation of a merged profile. */
    fun validateMerged(
        profile: Map<String, Any?>,
        route: String?,
        fallbackTo: String?,
    ): List<ConfigError> {
        val errors = mutableListOf<ConfigError>()
        for (key in profile.keys) {
            if (key !in KnownTopLevel) {
                errors += ConfigError(key, "unknown top-level key")
            }
        }
        val rawEngine = profile[ExecutionEngine.Key]
        val engine = executionEngine(profile)
        if (engine == null) {
            errors += ConfigError(
                ExecutionEngine.Key,
                "unknown execution engine: ${rawEngine?.toString()?.take(32)}",
            )
            return errors
        }
        if (engine == ExecutionEngine.NATIVE) {
            if (profile.containsKey(ExecutionEngine.Section)) {
                errors += ConfigError(ExecutionEngine.Section, "not allowed for the native engine")
            }
            errors += validateNative(profile, route, fallbackTo)
        } else {
            errors += validateH80gt(profile)
        }
        return errors
    }

    private fun validateNative(
        profile: Map<String, Any?>,
        route: String?,
        fallbackTo: String?,
    ): List<ConfigError> {
        val errors = mutableListOf<ConfigError>()
        for (key in RequiredTopLevel) {
            if (!profile.containsKey(key)) {
                errors += ConfigError(key, "missing required key")
            }
        }
        if (route == null || route !in RouteNames) {
            errors += ConfigError("route", "missing or unknown route")
        }
        if (fallbackTo != null && fallbackTo != "none" && fallbackTo !in RouteNames) {
            errors += ConfigError("fallback.to", "unknown fallback route")
        }
        val task = profile["task_struct"] as? Map<*, *>
        if (task == null) {
            errors += ConfigError("task_struct", "not an object")
        } else {
            for (field in RequiredTaskStruct) {
                if (task[field] !is Number) errors += ConfigError("task_struct.$field", "missing")
            }
        }
        val cred = profile["cred"] as? Map<*, *>
        if (cred == null) {
            errors += ConfigError("cred", "not an object")
        } else {
            for (field in RequiredCred) {
                when (val value = cred[field]) {
                    null -> errors += ConfigError("cred.$field", "missing")
                    !is Number -> errors += ConfigError("cred.$field", "not a number")
                    else -> if (value.toLong() == 0L) {
                        errors += ConfigError("cred.$field", "zero")
                    }
                }
            }
        }
        val offset = profile["offset"] as? Map<*, *>
        if (offset == null) {
            errors += ConfigError("offset", "not an object")
        } else {
            for (field in RequiredOffset) {
                when (val value = offset[field]) {
                    null -> errors += ConfigError("offset.$field", "missing")
                    !is Number -> errors += ConfigError("offset.$field", "not a number")
                    else -> if (value.toLong() == 0L) {
                        errors += ConfigError("offset.$field", "zero")
                    }
                }
            }
        }
        return errors
    }

    /**
     * An H80GT profile carries no native document: the offsets, the credential
     * template and the route geometry belong to the native pipeline only. A
     * profile that mixes both is rejected instead of silently ignoring half of
     * itself, and the chain always needs shell identity (Shizuku).
     */
    private fun validateH80gt(profile: Map<String, Any?>): List<ConfigError> {
        val errors = mutableListOf<ConfigError>()
        for (key in NativeOnlyTopLevel) {
            if (profile.containsKey(key)) {
                errors += ConfigError(key, "not allowed for the h80gt engine")
            }
        }
        for (key in listOf("release", "schema_version", "kernel_major")) {
            if (!profile.containsKey(key)) errors += ConfigError(key, "missing required key")
        }
        if ((profile["schema_version"] as? Number)?.toInt() != 1) {
            errors += ConfigError("schema_version", "unsupported profile schema")
        }
        if ((profile["kernel_major"] as? Number)?.toInt() !in 5..6) {
            errors += ConfigError("kernel_major", "invalid kernel_major")
        }
        if ((profile["release"] as? String).isNullOrBlank()) {
            errors += ConfigError("release", "missing")
        }
        if ((profile["recommend_shizuku"] as? Number)?.toLong() != 1L) {
            errors += ConfigError("recommend_shizuku", "must be 1 for the h80gt engine")
        }
        errors += H80gtSpec.parse(profile).second
        return errors
    }

    /** Flattens the `execution` object into path -> value. */
    fun executionFromMerged(profile: Map<String, Any?>): Map<String, ULong> {
        val execution = profile["execution"] as? Map<*, *> ?: return emptyMap()
        val out = LinkedHashMap<String, ULong>()
        fun walk(prefix: String, node: Map<*, *>) {
            for ((rawKey, value) in node) {
                val key = rawKey.toString()
                val path = if (prefix.isEmpty()) key else "$prefix.$key"
                when (value) {
                    is Map<*, *> -> walk(path, value)
                    is Number -> out[path] = value.toLong().toULong()
                }
            }
        }
        walk("", execution)
        return out
    }

    private val RouteNames = RouteKind.entries.map { it.token }
}
