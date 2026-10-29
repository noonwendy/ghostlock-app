package com.ghostlock.app.data.h80gt

/**
 * Renders the authoritative H80GT loader template.
 *
 * The template is shipped byte-identical to
 * `GhostLock-H80GT@33541d1:ksu/tools/ksu_loader.tmpl` (LF), and the
 * substitutions are exactly the ones `release/setup.sh` performs on the device
 * flow. Nothing else is allowed to template the loader: a second copy would
 * drift from the one the H80GT project actually ships.
 */
object H80gtLoader {
    /** File name the exploit's anchor executes inside the run directory. */
    const val StagedName = "ksu_loader.sh"

    /** Values `release/setup.sh` renders; the device flow, not the PC debug one. */
    const val FreezeHisecd = "1"
    const val AllowShell = "0"
    const val Stages = "1"
    const val Enforce = "1"

    private val Placeholder = Regex("@[A-Z0-9_]+@")

    /**
     * @throws IllegalArgumentException when the template is not the shipped LF
     *   file or still carries a placeholder after substitution.
     */
    fun render(template: String, runDir: String, stamp: String): String {
        require(template.isNotEmpty()) { "loader template is empty" }
        require(!template.contains('\r')) { "loader template must not contain CR (CRLF checkout?)" }
        require(runDir.startsWith("/")) { "run dir must be absolute" }
        require(stamp.isNotEmpty()) { "run stamp is empty" }
        val rendered = template
            .replace("@RUNDIR@", runDir)
            .replace("@STAMP@", stamp)
            .replace("@FREEZE_HISECD@", FreezeHisecd)
            .replace("@ALLOW_SHELL@", AllowShell)
            .replace("@STAGES@", Stages)
            .replace("@ENFORCE@", Enforce)
        val leftover = Placeholder.findAll(rendered).map { it.value }.toList()
        require(leftover.isEmpty()) { "unrendered loader placeholders: ${leftover.distinct()}" }
        return rendered
    }
}
