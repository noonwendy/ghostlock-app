package com.ghostlock.app.data.h80gt

import com.ghostlock.app.data.HoconSupport
import com.ghostlock.app.data.asValueMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-run state, the reboot bookkeeping and the probe parsers. A stale record
 * must never let a retry run on the same boot, and a device read must never be
 * misread as "clean".
 */
class H80gtRunStateTest {
    private val record = H80gtRunStateCodec.initial("20261029_220000", 1_000L, 500L)
        .with(H80gtStage.PREFLIGHT, H80gtStatus.COMPLETED)
        .with(H80gtStage.STAGE, H80gtStatus.COMPLETED)
        .with(H80gtStage.LAUNCH, H80gtStatus.IN_PROGRESS)
        .finished(H80gtOutcome.FAILED, H80gtStage.EXPLOIT, needsReboot = true, reason = "timeout")

    @Test
    fun `a record survives the codec`() {
        val decoded = H80gtRunStateCodec.decode(H80gtRunStateCodec.encode(record))
        assertEquals(record, decoded)
        assertEquals(H80gtStatus.COMPLETED, decoded!!.statusOf(H80gtStage.STAGE))
        assertEquals(H80gtStatus.NOT_STARTED, decoded.statusOf(H80gtStage.RESTORE))
    }

    @Test
    fun `the record is valid HOCON`() {
        val parsed = HoconSupport.parseValue(H80gtRunStateCodec.encode(record)).asValueMap()
        assertEquals("failed", parsed!!["outcome"])
        assertEquals(true, parsed["needs_reboot"])
    }

    @Test
    fun `a record from another schema is not decoded`() {
        val text = H80gtRunStateCodec.encode(record).replace("\"schema_version\": 1", "\"schema_version\": 2")
        assertNull(H80gtRunStateCodec.decode(text))
        assertNull(H80gtRunStateCodec.decode("not a document"))
    }

    @Test
    fun `the reboot gate compares boot instants, not wall clocks`() {
        val boot = H80gtBootClock.bootInstant(nowMillis = 10_000_000L, elapsedRealtimeMillis = 4_000_000L)
        assertEquals(6_000_000L, boot)
        /* Same boot: clock corrections inside the tolerance keep the block. */
        assertFalse(H80gtBootClock.rebootedSince(boot, boot + 30_000L))
        assertFalse(H80gtBootClock.rebootedSince(boot, boot - 30_000L))
        /* A later boot instant means the device rebooted since the record. */
        assertTrue(H80gtBootClock.rebootedSince(boot, boot + 600_000L))
    }

    @Test
    fun `module and knob parsers are conservative`() {
        assertTrue(H80gtShellProbe.parseModules("kernelsu 999 0 - Live 0x0\nfoo 1 0 - Live 0x0"))
        assertFalse(H80gtShellProbe.parseModules("foo 1 0 - Live 0x0"))
        assertEquals(true, H80gtShellProbe.parseEnforce("1\n"))
        assertEquals(false, H80gtShellProbe.parseEnforce("0\n"))
        assertNull(H80gtShellProbe.parseEnforce(""))
        assertEquals(true, H80gtShellProbe.parseSigEnforce("Y\n"))
        assertEquals(false, H80gtShellProbe.parseSigEnforce("N\n"))
        assertNull(H80gtShellProbe.parseSigEnforce(null))
    }

    @Test
    fun `kallsyms mount and boot id parsers detect the hijack leftovers`() {
        val mounted = "/dev/block/x /proc/kallsyms ext4 rw 0 0\n"
        val clean = "/dev/block/x /proc 0 0\n"
        assertTrue(H80gtShellProbe.parseKallsymsMounted(mounted))
        assertFalse(H80gtShellProbe.parseKallsymsMounted(clean))
        val uuid = "1a2b3c4d-5e6f-7a8b-9c0d-1e2f3a4b5c6d"
        assertEquals(uuid, H80gtShellProbe.parseBootId("$uuid\n"))
        assertNull(H80gtShellProbe.parseBootId("garbage"))
        assertNull(H80gtShellProbe.parseBootId(null))
    }

    @Test
    fun `a live chain is detected from process cmdlines`() {
        val cmdlines = listOf(
            "/system/bin/sh /data/local/tmp/ksu_run_20261029_220000/ksu_loader.sh",
            "/data/local/tmp/ksu_run_20261029_220000/exploit",
        )
        assertTrue(H80gtShellProbe.parseChainAlive(cmdlines, "/data/local/tmp"))
        assertFalse(H80gtShellProbe.parseChainAlive(listOf("/system/bin/logd"), "/data/local/tmp"))
    }
}
