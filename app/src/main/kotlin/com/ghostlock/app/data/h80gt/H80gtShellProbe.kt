package com.ghostlock.app.data.h80gt

import android.os.StatFs
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Every device read the specialized chain needs, in one place. It runs inside
 * the Shizuku UserService (uid 2000), which is the only identity that can read
 * `/data/local/tmp`, `/proc/modules` and the SELinux knobs without root.
 *
 * All parsers are pure companion functions so the decisions they feed are
 * unit-tested without a device.
 */
class H80gtShellProbe(
    private val runRoot: String = DefaultRunRoot,
    private val procRoot: File = File("/proc"),
) {
    companion object {
        const val DefaultRunRoot = "/data/local/tmp"
        const val ModuleMarker = "kernelsu"

        /** True when any loaded module matches [ModuleMarker]. */
        fun parseModules(text: String): Boolean =
            text.lineSequence().any { it.contains(ModuleMarker, ignoreCase = true) }

        fun parseEnforce(text: String?): Boolean? = when (text?.trim()) {
            "1" -> true
            "0" -> false
            else -> null
        }

        fun parseSigEnforce(text: String?): Boolean? = when (text?.trim()) {
            "Y", "1" -> true
            "N", "0" -> false
            else -> null
        }

        /** `/proc/self/mounts`: the loader bind-mounts `/proc/kallsyms` while loading. */
        fun parseKallsymsMounted(mounts: String): Boolean =
            mounts.lineSequence().any { line ->
                line.split(' ', limit = 3).getOrNull(1) == "/proc/kallsyms"
            }

        private val BootIdPattern =
            Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

        fun parseBootId(text: String?): String? {
            val line = text?.trim()?.lineSequence()?.firstOrNull()?.trim().orEmpty()
            return line.takeIf { BootIdPattern.matches(it) }
        }

        fun parseUptime(text: String?): Long? =
            text?.trim()?.substringBefore(' ')?.toDoubleOrNull()?.toLong()

        /** A chain is alive when a loader or an exploit from a run directory is still running. */
        fun parseChainAlive(cmdlines: List<String>, runRoot: String): Boolean =
            cmdlines.any { line ->
                line.contains("ksu_loader.sh") ||
                    (line.contains("$runRoot/ksu_run_") && line.contains("exploit"))
            }
    }

    fun readText(path: String): String? =
        runCatching { File(path).readText() }.getOrNull()

    /** null when `/proc/modules` is unreadable (not the same as "not loaded"). */
    fun moduleLoaded(): Boolean? = readText("/proc/modules")?.let(::parseModules)

    fun enforcing(): Boolean? = parseEnforce(readText("/sys/fs/selinux/enforce"))

    fun sigEnforce(): Boolean? = parseSigEnforce(readText("/sys/module/module/parameters/sig_enforce"))

    fun kptrRestrict(): String? = readText("/proc/sys/kernel/kptr_restrict")?.trim()

    fun kallsymsMounted(): Boolean? =
        readText("/proc/self/mounts")?.let(::parseKallsymsMounted)

    /** A valid, readable boot_id proves the module restored the hijacked pointer. */
    fun bootId(): String? = parseBootId(readText("/proc/sys/kernel/random/boot_id"))

    fun uptimeSeconds(): Long? = parseUptime(readText("/proc/uptime"))

    fun selinuxContext(): String? =
        readText("/proc/self/attr/current")?.trim()?.trimEnd('\u0000')?.takeIf { it.isNotEmpty() }

    fun freeBytes(): Long = runCatching { StatFs(runRoot).availableBytes }.getOrDefault(0L)

    /** Reads every process cmdline; the shell uid can see them all. */
    fun processCmdlines(): List<String> =
        runCatching {
            procRoot.listFiles()
                ?.asSequence()
                ?.filter { it.isDirectory && it.name.all(Char::isDigit) }
                ?.mapNotNull { dir ->
                    val pid = dir.name.toIntOrNull() ?: return@mapNotNull null
                    if (pid == android.os.Process.myPid()) return@mapNotNull null
                    /* One unreadable process (SELinux denial, kernel thread) must
                     * not hide every other cmdline: the chain-liveness check
                     * depends on this list being complete for readable pids. */
                    runCatching {
                        File(dir, "cmdline").readBytes().toString(Charsets.UTF_8)
                            .replace('\u0000', ' ')
                    }.getOrNull()
                }
                ?.filter { it.isNotBlank() }
                ?.toList()
                .orEmpty()
        }.getOrDefault(emptyList())

    fun chainAlive(): Boolean = parseChainAlive(processCmdlines(), runRoot)

    /** Best effort; a failure is reported, never fatal on its own. */
    fun forceStop(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        return runCatching {
            val process = ProcessBuilder("/system/bin/sh", "-c", "am force-stop $packageName")
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.to(File("/dev/null")))
                .start()
            process.waitFor(15, TimeUnit.SECONDS) && process.exitValue() == 0
        }.getOrDefault(false)
    }

    fun runDir(stamp: String): File = File(runRoot, "ksu_run_$stamp")
}
