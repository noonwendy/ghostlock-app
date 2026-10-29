package com.ghostlock.app.data.h80gt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The loader is the H80GT project's authoritative script; the app may only
 * perform the substitutions `release/setup.sh` performs. These cases pin the
 * substitution values and refuse anything a CRLF checkout or a template change
 * could break on the device.
 */
class H80gtLoaderTest {
    private val template = """
        #!/system/bin/sh
        RD=@RUNDIR@
        STAMP=@STAMP@
        FREEZE=@FREEZE_HISECD@
        SHELL=@ALLOW_SHELL@
        STAGES=@STAGES@
        ENFORCE=@ENFORCE@
    """.trimIndent() + "\n"

    @Test
    fun `the device flow substitutions are the setup sh ones`() {
        val rendered = H80gtLoader.render(template, "/data/local/tmp/ksu_run_20261029_220000", "20261029_220000")
        assertTrue(rendered.contains("RD=/data/local/tmp/ksu_run_20261029_220000"))
        assertTrue(rendered.contains("STAMP=20261029_220000"))
        assertTrue(rendered.contains("FREEZE=1"))
        assertTrue(rendered.contains("SHELL=0"))
        assertTrue(rendered.contains("STAGES=1"))
        assertTrue(rendered.contains("ENFORCE=1"))
        assertFalse(rendered.contains("@"))
    }

    @Test
    fun `a leftover placeholder is refused`() {
        val broken = template + "echo @SOMETHING_NEW@\n"
        assertThrows(IllegalArgumentException::class.java) {
            H80gtLoader.render(broken, "/data/local/tmp/x", "x")
        }
    }

    @Test
    fun `a CRLF template is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            H80gtLoader.render(template.replace("\n", "\r\n"), "/data/local/tmp/x", "x")
        }
    }

    @Test
    fun `an empty template or stamp is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            H80gtLoader.render("", "/data/local/tmp/x", "x")
        }
        assertThrows(IllegalArgumentException::class.java) {
            H80gtLoader.render(template, "/data/local/tmp/x", "")
        }
    }

    @Test
    fun `the staged name is the one the exploit anchor executes`() {
        assertEquals("ksu_loader.sh", H80gtLoader.StagedName)
    }
}
