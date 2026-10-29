package com.ghostlock.app.data.h80gt

import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The specialized H80GT run, executed inside the Shizuku UserService (uid 2000,
 * no seccomp).
 *
 * It owns nothing that outlives the run: it stages the payload into a fresh
 * `/data/local/tmp/ksu_run_<stamp>` directory, starts the exploit detached,
 * supervises the loader's progress through its own logs and the live kernel
 * state, and reports each stage. It never kills the chain — a killed parked
 * waiter can panic the kernel — and it never retries: the H80GT contract is
 * one attempt per boot.
 */
class H80gtExecutor(
    private val apk: File,
    private val payloadId: String,
    private val selectedManager: String?,
    private val log: (String) -> Unit,
    private val status: (stage: String, status: String) -> Unit,
    private val probe: H80gtShellProbe = H80gtShellProbe(),
    private val now: () -> Long = System::currentTimeMillis,
    private val sleeper: (Long) -> Unit = { sleeperDefault(it) },
) {
    private companion object {
        const val LaunchTimeoutMs = 15_000L
        const val ExploitTimeoutMs = 900_000L
        const val ModuleTimeoutMs = 300_000L
        const val UserspaceTimeoutMs = 300_000L
        const val RestoreTimeoutMs = 180_000L
        const val PollMs = 1_000L
        const val LINE_POLL_MS = 300L

        /** Stop relaying past this many lines; marker lines are always relayed. */
        const val MaxRelayedLines = 12_000

        val ExploitOk = listOf("sysctl-route-ok")
        val ExploitRooted = "anchor rooted"
        val ExploitSigOff = "sig_enforce disabled"
        val ExploitFailed = listOf("short route failed")
        val LoaderAbort = listOf(
            "sig_enforce was not disabled",
            "bind-mount failed",
            "policy check failed",
        )
        val LoaderModuleLoaded = "KernelSU module loaded"
        val LoaderUserspaceDone = "install exit="
        val LoaderRebootNote = "the boot_id kernel pointer is still redirected"

        fun sleeperDefault(millis: Long) {
            try {
                Thread.sleep(millis)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    /** Everything the caller needs to persist; also the shape of the final log line. */
    data class Result(
        val exitCode: Int,
        val outcome: H80gtOutcome,
        val failedStage: H80gtStage?,
        val needsReboot: Boolean,
        val reason: String,
        val runDir: String?,
    )

    private val relayed = java.util.concurrent.atomic.AtomicInteger(0)

    fun run(bootInstant: Long): Result {
        val startedAt = now()
        val stamp = newStamp(startedAt)
        val runDir = probe.runDir(stamp)

        status(H80gtStage.PREFLIGHT.token, H80gtStatus.IN_PROGRESS.token)
        val catalog = runCatching { H80gtPayloadCatalog.fromApk(apk) }.getOrElse { error ->
            return refuse(H80gtStage.PREFLIGHT, "cannot read ${apk.name}: ${error.message}")
        }
        val payload = catalog.payload(payloadId)
            ?: return refuse(
                H80gtStage.PREFLIGHT,
                "${H80gtIssueCode.PAYLOAD_MISSING}: $payloadId is not bundled",
            )
        val facts = H80gtDeviceFacts(
            model = android.os.Build.MODEL.orEmpty(),
            fingerprint = android.os.Build.FINGERPRINT.orEmpty(),
            release = System.getProperty("os.version", "").orEmpty(),
        )
        val shellFacts = H80gtShellFacts(
            moduleLoaded = probe.moduleLoaded() ?: false,
            chainAlive = probe.chainAlive(),
            enforcing = probe.enforcing(),
            freeBytes = probe.freeBytes(),
        )
        val spec = H80gtSpecView(payload)
        val verdict = H80gtPreflight.shellSide(
            spec = spec.toProfileSpec(),
            payload = payload,
            device = facts,
            shell = shellFacts,
        )
        log("<s> h80gt preflight: device=${facts.model} release=${facts.release}")
        log("<s> h80gt preflight: module=${shellFacts.moduleLoaded} chain=${shellFacts.chainAlive} " +
            "enforce=${shellFacts.enforcing} free=${shellFacts.freeBytes / (1024 * 1024)}MiB")
        if (!verdict.ok) {
            verdict.blockers.forEach { log("<s> h80gt blocked: $it") }
            status(H80gtStage.PREFLIGHT.token, H80gtStatus.FAILED.token)
            return refuse(H80gtStage.PREFLIGHT, verdict.describe())
        }
        status(H80gtStage.PREFLIGHT.token, H80gtStatus.COMPLETED.token)

        /* --- stage: a fresh directory per attempt, every byte verified --- */
        status(H80gtStage.STAGE.token, H80gtStatus.IN_PROGRESS.token)
        val staged = stage(payload, runDir)
        if (staged != null) {
            status(H80gtStage.STAGE.token, H80gtStatus.FAILED.token)
            return refuse(H80gtStage.STAGE, staged)
        }
        status(H80gtStage.STAGE.token, H80gtStatus.COMPLETED.token)

        /* --- launch: the manager must not probe the chain while it runs --- */
        status(H80gtStage.LAUNCH.token, H80gtStatus.IN_PROGRESS.token)
        val managerPackage = H80gtManager.resolvePackage(selectedManager, payload)
        if (probe.forceStop(managerPackage)) {
            log("<k> manager stopped: $managerPackage")
        } else {
            log("<k> manager not stopped (not installed or already stopped): $managerPackage")
        }
        val glLog = File(runDir, "gl.log")
        val launch = launch(runDir)
        log("<k> chain launched: ${launch.command}")
        val launched = waitFor(LaunchTimeoutMs) { glLog.isFile }
        if (!launched) {
            status(H80gtStage.LAUNCH.token, H80gtStatus.FAILED.token)
            return finish(
                H80gtStage.LAUNCH,
                "the exploit never produced gl.log (exit=${launch.exitCode})",
                /* Nothing ran: the kernel was not touched. */
                needsReboot = launch.exitCode == null,
                runDir = runDir,
            )
        }
        status(H80gtStage.LAUNCH.token, H80gtStatus.COMPLETED.token)
        val tailer = startTailer(runDir)
        try {
            /* --- exploit markers --- */
            status(H80gtStage.EXPLOIT.token, H80gtStatus.IN_PROGRESS.token)
            val exploit = awaitExploit(glLog, tailer)
            if (exploit != null) {
                status(H80gtStage.EXPLOIT.token, H80gtStatus.FAILED.token)
                return finish(H80gtStage.EXPLOIT, exploit, needsReboot = true, runDir = runDir)
            }
            status(H80gtStage.EXPLOIT.token, H80gtStatus.COMPLETED.token)

            /* --- module --- */
            status(H80gtStage.MODULE.token, H80gtStatus.IN_PROGRESS.token)
            val module = awaitModule(runDir)
            if (module != null) {
                status(H80gtStage.MODULE.token, H80gtStatus.FAILED.token)
                return finish(H80gtStage.MODULE, module, needsReboot = true, runDir = runDir)
            }
            status(H80gtStage.MODULE.token, H80gtStatus.COMPLETED.token)

            /* --- userspace bring-up (ksud stages) --- */
            status(H80gtStage.USERSPACE.token, H80gtStatus.IN_PROGRESS.token)
            val userspace = awaitUserspace(runDir)
            if (userspace != null) {
                status(H80gtStage.USERSPACE.token, H80gtStatus.FAILED.token)
                log("<s> " + verifyRestore().joinToString("; "))
                return finish(H80gtStage.USERSPACE, userspace, needsReboot = true, runDir = runDir)
            }
            status(H80gtStage.USERSPACE.token, H80gtStatus.COMPLETED.token)

            /* --- restore: the loader's own contract, checked live --- */
            status(H80gtStage.RESTORE.token, H80gtStatus.IN_PROGRESS.token)
            val restore = awaitRestore()
            if (restore != null) {
                status(H80gtStage.RESTORE.token, H80gtStatus.FAILED.token)
                return finish(H80gtStage.RESTORE, restore, needsReboot = true, runDir = runDir)
            }
            status(H80gtStage.RESTORE.token, H80gtStatus.COMPLETED.token)
        } finally {
            tailer.stop()
        }

        log("<k> h80gt chain ready; evidence in $runDir")
        status("outcome", H80gtOutcome.READY.token)
        status("reboot", "none")
        return Result(0, H80gtOutcome.READY, null, false, "", runDir.absolutePath)
    }

    /* ------------------------------------------------------------------ stage */

    /** Returns null on success, or the refusal reason. Removes the directory on failure. */
    private fun stage(payload: H80gtPayload, runDir: File): String? {
        val failure = runCatching {
            require(runDir.mkdirs() || runDir.isDirectory) { "cannot create ${runDir.absolutePath}" }
            for (artifact in payload.staged) {
                val bytes = H80gtPayloadCatalog.readAssetBytes(apk, artifact.path)
                    ?: return@runCatching "missing asset ${artifact.path}"
                require(bytes.size.toLong() == artifact.size) {
                    "${artifact.name}: size ${bytes.size} != ${artifact.size}"
                }
                val digest = H80gtPayloadCatalog.sha256(bytes)
                require(digest == artifact.sha256) {
                    "${artifact.name}: sha256 $digest != ${artifact.sha256}"
                }
                val target = File(runDir, artifact.name)
                target.writeBytes(bytes)
                if (artifact.executable) require(target.setExecutable(true)) {
                    "cannot mark ${artifact.name} executable"
                }
                log("<k> staged ${artifact.name} (${artifact.role}, ${bytes.size} bytes, sha256 ok)")
            }
            val templateArtifact = payload.loaderTemplate
                ?: return@runCatching "payload has no loader template"
            val template = H80gtPayloadCatalog.readAsset(apk, templateArtifact.path)
                ?: return@runCatching "missing asset ${templateArtifact.path}"
            val templateBytes = template.toByteArray(Charsets.UTF_8)
            require(templateBytes.size.toLong() == templateArtifact.size) {
                "${templateArtifact.name}: size ${templateBytes.size} != ${templateArtifact.size}"
            }
            require(H80gtPayloadCatalog.sha256(templateBytes) == templateArtifact.sha256) {
                "${templateArtifact.name}: sha256 mismatch"
            }
            val script = H80gtLoader.render(template, runDir.absolutePath, runDir.name.removePrefix("ksu_run_"))
            val loaderFile = File(runDir, H80gtLoader.StagedName)
            loaderFile.writeText(script)
            require(loaderFile.setExecutable(true)) { "cannot mark the loader executable" }
            log("<k> staged ${H80gtLoader.StagedName} (rendered, ${script.length} bytes)")
            for (name in payload.staged.map { it.name } + H80gtLoader.StagedName) {
                require(File(runDir, name).isFile) { "staged file missing: $name" }
            }
            null
        }.getOrElse { error ->
            runCatching { runDir.deleteRecursively() }
            error.message ?: error::class.java.simpleName
        }
        return failure
    }

    /* ----------------------------------------------------------------- launch */

    private data class Launch(val command: String, val exitCode: Int?)

    private fun launch(runDir: File): Launch {
        val dir = runDir.absolutePath
        val command = "cd '$dir' && nohup env KSU_RUNDIR='$dir' GL_ANCHOR_LOG='$dir/gl_anchor.log' " +
            "./exploit > gl.log 2>&1 </dev/null &"
        val exitCode = runCatching {
            val process = ProcessBuilder("/system/bin/sh", "-c", command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.to(File("/dev/null")))
                .start()
            if (process.waitFor(10, TimeUnit.SECONDS)) process.exitValue() else null
        }.getOrNull()
        return Launch(command, exitCode)
    }

    /* ---------------------------------------------------------------- polling */

    private fun awaitExploit(glLog: File, tailer: Tailer): String? {
        val deadline = now() + ExploitTimeoutMs
        while (now() < deadline) {
            val text = tailer.snapshotGl()
            if (ExploitFailed.any { text.contains(it) }) {
                return "the exploit reported a route failure (see gl.log)"
            }
            if (ExploitOk.any { text.contains(it) } ||
                (text.contains(ExploitRooted) && text.contains(ExploitSigOff))
            ) {
                return null
            }
            sleeper(PollMs)
        }
        return "the exploit produced no success marker within ${ExploitTimeoutMs / 1000}s"
    }

    private fun awaitModule(runDir: File): String? {
        val deadline = now() + ModuleTimeoutMs
        val loaderLog = File(runDir, "ksu_load.log")
        while (now() < deadline) {
            if (probe.moduleLoaded() == true) return null
            val text = runCatching { loaderLog.readText() }.getOrDefault("")
            if (LoaderAbort.any { text.contains(it) }) {
                return "the loader aborted: " + LoaderAbort.first { text.contains(it) }
            }
            if (text.contains(LoaderRebootNote)) {
                return "the loader reported that the module was not loaded and a reboot is required"
            }
            sleeper(PollMs)
        }
        return "KernelSU was not in /proc/modules within ${ModuleTimeoutMs / 1000}s"
    }

    private fun awaitUserspace(runDir: File): String? {
        val deadline = now() + UserspaceTimeoutMs
        val loaderLog = File(runDir, "ksu_load.log")
        while (now() < deadline) {
            val text = runCatching { loaderLog.readText() }.getOrDefault("")
            if (text.contains(LoaderUserspaceDone)) return null
            if (!text.isEmpty() && text.contains(LoaderModuleLoaded) && text.contains("KSU_LOAD_DONE")) {
                /* The loader finished without the stages: the module is live but
                 * there is no userspace, which is a failure, not a success. */
                return "the loader finished without the ksud stage output"
            }
            sleeper(PollMs)
        }
        return "the ksud userspace stages did not finish within ${UserspaceTimeoutMs / 1000}s"
    }

    private fun awaitRestore(): String? {
        val deadline = now() + RestoreTimeoutMs
        var last = ""
        while (now() < deadline) {
            val problems = verifyRestore()
            if (problems.isEmpty()) return null
            last = problems.joinToString("; ")
            sleeper(PollMs)
        }
        return "the device did not return to its resting state: $last"
    }

    /** Empty when every post-condition of the loader's contract holds. */
    private fun verifyRestore(): List<String> {
        val problems = mutableListOf<String>()
        val enforcing = probe.enforcing()
        if (enforcing != true) problems += "SELinux enforcing=$enforcing"
        /* Honor's SELinux policy denies the shell uid reads of these knobs
         * (verified on 9.0.0.230), so null means "cannot verify", never "the
         * loader failed to restore". An explicit false is still a failure, and
         * the loader logs its own restore line either way. */
        probe.sigEnforce()?.let { sig -> if (!sig) problems += "sig_enforce=false" }
        val mounted = probe.kallsymsMounted()
        if (mounted != false) problems += "kallsyms bind mount present=$mounted"
        if (probe.moduleLoaded() != true) problems += "kernelsu module missing"
        /* Only meaningful once the module restored the hijacked pointer. */
        if (probe.moduleLoaded() == true) {
            val first = probe.bootId()
            if (first == null) {
                problems += "boot_id unreadable"
            } else {
                sleeper(150)
                val second = probe.bootId()
                if (second != first) problems += "boot_id unstable"
            }
        }
        return problems
    }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = now() + timeoutMs
        while (now() < deadline) {
            if (condition()) return true
            sleeper(PollMs)
        }
        return condition()
    }

    /* -------------------------------------------------------------- reporting */

    private fun refuse(stage: H80gtStage, reason: String): Result {
        log("<s> h80gt refused at ${stage.token}: $reason")
        status("outcome", H80gtOutcome.REFUSED.token)
        status("reboot", "none")
        return Result(1, H80gtOutcome.REFUSED, stage, false, reason, null)
    }

    private fun finish(
        stage: H80gtStage,
        reason: String,
        needsReboot: Boolean,
        runDir: File,
    ): Result {
        log("<s> h80gt failed at ${stage.token}: $reason")
        log("<s> h80gt evidence: ${runDir.absolutePath}")
        if (needsReboot) {
            log("<s> reboot the device before the next attempt (the boot_id pointer may be redirected)")
        }
        status("outcome", H80gtOutcome.FAILED.token)
        status("reboot", if (needsReboot) "required" else "none")
        return Result(1, H80gtOutcome.FAILED, stage, needsReboot, reason, runDir.absolutePath)
    }

    private fun newStamp(startedAt: Long): String {
        val format = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT)
        var candidate = format.format(Date(startedAt))
        var attempts = 0
        while (probe.runDir(candidate).exists() && attempts < 3) {
            /* A fresh directory per attempt is a correctness requirement, never
             * reuse one: the exploit's stray writes can poison inode metadata. */
            sleeper(1_000)
            candidate = format.format(Date(now()))
            attempts++
        }
        return candidate
    }

    /* ----------------------------------------------------------------- tailer */

    /**
     * Polls the two logs the chain writes and forwards complete lines. The
     * chain's stdout is a file, never a pipe, so this can never apply
     * backpressure to a kernel-critical path.
     */
    private inner class Tailer(private val runDir: File) {
        private val stopped = AtomicBoolean(false)
        private val lock = Any()
        private val gl = Tail(File(runDir, "gl.log"), capture = true)
        private val ksu = Tail(File(runDir, "ksu_load.log"), capture = false)
        private val thread = Thread({ loop() }, "h80gt-log-tailer").apply { isDaemon = true }

        /** One append-only log file with its read offset and partial line. */
        private inner class Tail(val file: File, val capture: Boolean) {
            var offset = 0L
            val pending = StringBuilder()
            val text = StringBuilder()

            /** Reads what is new; returns complete lines. Caller holds [lock]. */
            fun poll(onLine: (String) -> Unit) {
                if (!file.isFile) return
                runCatching {
                    RandomAccessFile(file, "r").use { handle ->
                        if (offset > handle.length()) {
                            offset = 0
                            pending.clear()
                            if (capture) text.clear()
                        }
                        handle.seek(offset)
                        while (true) {
                            val byte = handle.read()
                            if (byte == -1) break
                            if (byte == '\n'.code) {
                                val line = pending.toString()
                                pending.clear()
                                offset = handle.filePointer
                                if (line.isNotEmpty()) {
                                    if (capture) text.append(line).append('\n')
                                    onLine(line)
                                }
                            } else {
                                pending.append(byte.toChar())
                            }
                        }
                    }
                }
            }
        }

        fun start(): Tailer = apply { thread.start() }

        fun stop() {
            stopped.set(true)
            thread.interrupt()
            runCatching { thread.join(1_500) }
            synchronized(lock) {
                pollOnce()
            }
        }

        /** The exploit log accumulated so far, for marker matching. */
        fun snapshotGl(): String = synchronized(lock) { gl.text.toString() }

        private fun loop() {
            while (!stopped.get()) {
                synchronized(lock) { pollOnce() }
                try {
                    Thread.sleep(LINE_POLL_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }

        private fun pollOnce() {
            gl.poll { relay("gl", it) }
            ksu.poll { relay("ksu", it) }
        }

        private fun relay(tag: String, line: String) {
            val count = relayed.incrementAndGet()
            val marker = line.contains("sysctl-route-ok") || line.contains("anchor rooted") ||
                line.contains("sig_enforce") || line.contains("KernelSU") ||
                line.contains("ksu_load") || line.contains("error", ignoreCase = true) ||
                line.contains("fail", ignoreCase = true) || line.contains("exit=") ||
                line.contains("=== ")
            if (count <= MaxRelayedLines || marker) {
                log("[$tag] $line")
            } else if (count == MaxRelayedLines + 1) {
                log("[$tag] ... further lines suppressed (same count cap; see the device run directory)")
            }
        }
    }

    private fun startTailer(runDir: File): Tailer = Tailer(runDir).start()

    /**
     * The profile view the preflight needs. The run itself is driven by the
     * payload catalogue, but the target bindings must match the profile the
     * user selected, so they are re-derived here instead of trusted.
     */
    private class H80gtSpecView(private val payload: H80gtPayload) {
        fun toProfileSpec() = com.ghostlock.app.data.profile.H80gtSpec(
            release = payload.release,
            payload = payload.id,
            device = payload.device,
            fingerprint = payload.fingerprint,
            firmware = payload.firmware,
            managerVersion = payload.managerVersion,
        )
    }
}
