package com.ghostlock.app.data.h80gt

import com.ghostlock.app.data.HoconSupport
import com.ghostlock.app.data.asValueMap

/**
 * Stages of one H80GT run, in order. The tokens are persisted; renaming one
 * invalidates old records instead of silently reinterpreting them.
 */
enum class H80gtStage(val token: String) {
    PREFLIGHT("preflight"),
    STAGE("stage"),
    LAUNCH("launch"),
    EXPLOIT("exploit"),
    MODULE("module"),
    USERSPACE("userspace"),
    RESTORE("restore"),
    HANDOFF("handoff"),
    ;

    companion object {
        val Order = entries.toList()

        fun fromToken(token: String): H80gtStage? = entries.firstOrNull { it.token == token }
    }
}

enum class H80gtStatus(val token: String) {
    NOT_STARTED("not_start"),
    IN_PROGRESS("in_progress"),
    COMPLETED("completed"),
    FAILED("failed"),
    ;

    companion object {
        fun fromToken(token: String): H80gtStatus? = entries.firstOrNull { it.token == token }
    }
}

enum class H80gtOutcome(val token: String) {
    /** A chain is running (or the app died before it finished). */
    RUNNING("running"),
    READY("ready"),
    FAILED("failed"),
    /** Refused before staging: the device was not touched. */
    REFUSED("refused"),
    ;

    companion object {
        fun fromToken(token: String): H80gtOutcome? = entries.firstOrNull { it.token == token }
    }
}

/**
 * One run. `needsReboot` is the H80GT recovery contract: once the exploit has
 * been launched the boot_id ctl_table pointer may be redirected, so a failure
 * must be followed by a reboot before the next attempt.
 */
data class H80gtRunRecord(
    val stamp: String,
    val startedAt: Long,
    val bootInstant: Long,
    val outcome: H80gtOutcome,
    val failedStage: H80gtStage? = null,
    val needsReboot: Boolean = false,
    val reason: String = "",
    val stages: Map<H80gtStage, H80gtStatus> = emptyMap(),
) {
    fun statusOf(stage: H80gtStage): H80gtStatus =
        stages[stage] ?: H80gtStatus.NOT_STARTED

    fun with(stage: H80gtStage, status: H80gtStatus): H80gtRunRecord =
        copy(stages = stages + (stage to status))

    fun finished(outcome: H80gtOutcome, failedStage: H80gtStage?, needsReboot: Boolean, reason: String): H80gtRunRecord =
        copy(
            outcome = outcome,
            failedStage = failedStage,
            needsReboot = needsReboot,
            reason = reason.take(400),
        )
}

/** HOCON (JSON superset) codec for [H80gtRunRecord]; app-private, schema 1. */
object H80gtRunStateCodec {
    private const val SchemaVersion = 1

    fun initial(stamp: String, startedAt: Long, bootInstant: Long): H80gtRunRecord =
        H80gtRunRecord(
            stamp = stamp,
            startedAt = startedAt,
            bootInstant = bootInstant,
            outcome = H80gtOutcome.RUNNING,
            stages = H80gtStage.Order.associateWith { H80gtStatus.NOT_STARTED },
        )

    fun encode(record: H80gtRunRecord): String = buildString {
        append("{\n")
        append("  \"schema_version\": ").append(SchemaVersion).append(",\n")
        append("  \"stamp\": \"").append(record.stamp).append("\",\n")
        append("  \"started_at\": ").append(record.startedAt).append(",\n")
        append("  \"boot_instant\": ").append(record.bootInstant).append(",\n")
        append("  \"outcome\": \"").append(record.outcome.token).append("\",\n")
        append("  \"failed_stage\": \"").append(record.failedStage?.token ?: "").append("\",\n")
        append("  \"needs_reboot\": ").append(record.needsReboot).append(",\n")
        append("  \"reason\": \"").append(record.reason.replace("\\", "\\\\").replace("\"", "\\\""))
            .append("\",\n")
        append("  \"stages\": {\n")
        val entries = record.stages.entries.toList()
        entries.forEachIndexed { index, (stage, status) ->
            append("    \"").append(stage.token).append("\": \"").append(status.token).append('"')
            if (index + 1 < entries.size) append(',')
            append('\n')
        }
        append("  }\n}\n")
    }

    fun decode(text: String): H80gtRunRecord? {
        val root = runCatching { HoconSupport.parseValue(text).asValueMap() }.getOrNull() ?: return null
        if ((root["schema_version"] as? Number)?.toInt() != SchemaVersion) return null
        val stamp = root["stamp"] as? String ?: return null
        val outcome = H80gtOutcome.fromToken(root["outcome"] as? String ?: "") ?: return null
        val stages = linkedMapOf<H80gtStage, H80gtStatus>()
        root["stages"].asValueMap()?.forEach { (key, value) ->
            val stage = H80gtStage.fromToken(key) ?: return@forEach
            val status = H80gtStatus.fromToken(value as? String ?: "") ?: return@forEach
            stages[stage] = status
        }
        return H80gtRunRecord(
            stamp = stamp,
            startedAt = (root["started_at"] as? Number)?.toLong() ?: 0L,
            bootInstant = (root["boot_instant"] as? Number)?.toLong() ?: 0L,
            outcome = outcome,
            failedStage = H80gtStage.fromToken(root["failed_stage"] as? String ?: ""),
            needsReboot = root["needs_reboot"] == true,
            reason = root["reason"] as? String ?: "",
            stages = stages,
        )
    }
}

/**
 * Reboot bookkeeping. `System.currentTimeMillis()` alone cannot tell "same
 * boot" from "rebooted"; subtracting the elapsed-realtime clock yields the
 * wall-clock instant of boot, which changes only across a reboot.
 */
object H80gtBootClock {
    /** Tolerance for wall-clock corrections (NTP, timezone-free) across one boot. */
    const val ToleranceMillis = 60_000L

    fun bootInstant(nowMillis: Long, elapsedRealtimeMillis: Long): Long =
        nowMillis - elapsedRealtimeMillis

    /** True when [recorded] belongs to an earlier boot than [current]. */
    fun rebootedSince(recorded: Long, current: Long): Boolean =
        current > recorded + ToleranceMillis
}
