package com.ghostlock.app.data.profile

import com.ghostlock.app.data.ValueMap
import com.ghostlock.app.data.valueMapOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `engine` selector and the `h80gt` section are the only profile-schema
 * surface the specialized chain adds. These cases pin the fail-closed rules:
 * an unknown engine, a half-native profile and an incomplete target must all be
 * rejected instead of silently falling back to the native pipeline.
 */
class ExecutionEngineTest {
    private fun h80gtSection(): ValueMap = valueMapOf(
        "payload" to "annap-AGT-AN00_9.0.0.230",
        "device" to "AGT-AN00",
        "fingerprint" to "HONOR/AGT-AN00/HNAGT:15/TESTBUILD:user/release-keys",
        "firmware" to "9.0.0.230",
        "manager_version" to "3.3.0",
    )

    private fun h80gtProfile(): ValueMap = valueMapOf(
        "release" to "5.10.236-android12-9-gd671cf8c2b02",
        "schema_version" to 1L,
        "kernel_major" to 5L,
        "recommend_shizuku" to 1L,
        "engine" to "h80gt",
        "h80gt" to h80gtSection(),
    )

    @Test
    fun `an absent engine means native`() {
        assertEquals(ExecutionEngine.NATIVE, ProfileResolver.executionEngine(valueMapOf()))
        assertEquals("native", ExecutionEngine.NATIVE.token)
    }

    @Test
    fun `an unknown engine is not a fallback`() {
        assertNull(ProfileResolver.executionEngine(valueMapOf("engine" to "h9")))
        val errors = ProfileResolver.validateMerged(valueMapOf("engine" to "h9"), null, null)
        assertEquals(listOf("engine"), errors.map { it.fieldPath })
    }

    @Test
    fun `a complete h80gt profile validates`() {
        assertEquals(
            emptyList<ConfigError>(),
            ProfileResolver.validateMerged(h80gtProfile(), null, null),
        )
        val spec = ProfileResolver.h80gtSpec(h80gtProfile())
        assertNotNull(spec)
        assertEquals("annap-AGT-AN00_9.0.0.230", spec!!.payload)
        assertEquals("3.3.0", spec.managerVersion)
    }

    @Test
    fun `an h80gt profile must not carry native fields`() {
        val profile = h80gtProfile().apply {
            put("route", valueMapOf("select_stack" to valueMapOf("waiter_shift" to -2L)))
            put("task_struct", valueMapOf("prio" to 132L))
        }
        val fields = ProfileResolver.validateMerged(profile, null, null).map { it.fieldPath }.toSet()
        assertTrue(fields.contains("route"))
        assertTrue(fields.contains("task_struct"))
    }

    @Test
    fun `an h80gt profile requires the shell identity`() {
        val profile = h80gtProfile().apply { put("recommend_shizuku", 0L) }
        val fields = ProfileResolver.validateMerged(profile, null, null).map { it.fieldPath }
        assertTrue(fields.contains("recommend_shizuku"))
    }

    @Test
    fun `an incomplete h80gt section names every missing required field`() {
        val profile = h80gtProfile().apply {
            put("h80gt", valueMapOf("payload" to "p"))
        }
        val fields = ProfileResolver.validateMerged(profile, null, null).map { it.fieldPath }.toSet()
        assertEquals(setOf("h80gt.firmware", "h80gt.manager_version"), fields)
    }

    @Test
    fun `the device and fingerprint refinements are optional`() {
        val profile = h80gtProfile().apply {
            (get("h80gt") as ValueMap).remove("device")
            (get("h80gt") as ValueMap).remove("fingerprint")
        }
        assertEquals(emptyList<ConfigError>(), ProfileResolver.validateMerged(profile, null, null))
        val spec = ProfileResolver.h80gtSpec(profile)
        assertNotNull(spec)
        assertNull("device must stay unset", spec!!.device)
        assertNull("fingerprint must stay unset", spec.fingerprint)
    }

    @Test
    fun `a declared refinement is kept for the preflight`() {
        val spec = ProfileResolver.h80gtSpec(h80gtProfile())
        assertEquals("AGT-AN00", spec!!.device)
        assertNotNull(spec.fingerprint)
    }

    @Test
    fun `a native profile must not declare the h80gt section`() {
        val native = validNativeProfile().apply { put("h80gt", h80gtSection()) }
        val fields = ProfileResolver.validateMerged(native, "select_stack", "none").map { it.fieldPath }
        assertTrue(fields.contains("h80gt"))
    }

    @Test
    fun `a native profile keeps its own rules`() {
        assertEquals(
            emptyList<ConfigError>(),
            ProfileResolver.validateMerged(validNativeProfile(), "select_stack", "none"),
        )
        assertFalse(ProfileResolver.executionEngine(validNativeProfile())!!.isNative.not())
    }

    private fun validNativeProfile(): ValueMap = valueMapOf(
        "release" to "5.15.189-android13-8-00016-g51bba4309aac-ab14546557",
        "schema_version" to 1L,
        "kernel_major" to 5L,
        "route" to valueMapOf("select_stack" to valueMapOf("waiter_shift" to -2L)),
        "task_struct" to valueMapOf(
            "prio" to 132L, "normal_prio" to 140L, "sched_task_group" to 784L,
            "pi_lock" to 2156L, "pi_waiters" to 2176L, "pi_top_task" to 2192L,
            "pi_blocked_on" to 2200L, "pid" to 1480L, "tgid" to 1484L,
            "atomic_flags" to 1424L, "real_cred" to 1912L, "cred" to 1920L,
            "comm" to 1992L, "tasks" to 1112L, "seccomp" to 2240L,
        ),
        "cred" to valueMapOf("copy_size" to 136L, "caps_count" to 5L),
        "offset" to valueMapOf(
            "init_task" to 1L, "init_cred" to 2L, "root_task_group" to 3L,
            "selinux_enforcing" to 4L,
        ),
    )
}
