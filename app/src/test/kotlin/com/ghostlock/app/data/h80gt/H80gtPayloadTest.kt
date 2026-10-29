package com.ghostlock.app.data.h80gt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Verifies the packaged payload catalogue and the bytes staged next to it.
 * This is the V3 evidence: the APK's assets are exactly the pinned release
 * bundle members, byte for byte, and the loader template is the LF file the
 * H80GT project ships.
 */
class H80gtPayloadTest {
    private val assets = File("src/main/assets")

    /** Firmware -> kernel, from GhostLock-H80GT's own verified manifest. */
    private val verified = linkedMapOf(
        "8.0.0.128" to "5.10.168-android12-9-g804a21a522f0",
        "8.0.0.160" to "5.10.209-android12-9-g516fa23f87fc",
        "9.0.0.157" to "5.10.209-android12-9-ga3ff7ae30334",
        "9.0.0.200SP1" to "5.10.236-android12-9-gd1575ab10b30",
        "9.0.0.220SP2" to "5.10.236-android12-9-ga88335e8db40",
        "9.0.0.230" to "5.10.236-android12-9-gd671cf8c2b02",
    )

    private fun catalog(): H80gtPayloadCatalog =
        H80gtPayloadCatalog.parse(File(assets, H80gtPayloadCatalog.ManifestAsset).readText())

    @Test
    fun `the catalogue covers every verified firmware exactly once`() {
        val catalog = catalog()
        assertEquals(verified.size, catalog.payloads.size)
        for ((firmware, release) in verified) {
            val payload = catalog.payload("annap-AGT-AN00_$firmware")
            assertNotNull("$firmware: payload is not bundled", payload)
            assertEquals(release, payload!!.release)
            assertEquals(firmware, payload.firmware)
            assertEquals("3.3.0", payload.managerVersion)
            assertEquals("me.weishu.kernelsu", payload.managerPackage)
            assertEquals("verified", payload.status)
            assertEquals("$firmware: missing roles", emptyList<String>(), payload.missingRoles())
            assertNotNull("$firmware: no loader template", payload.loaderTemplate)
            /* Selection is by kernel string; no device-unique data is shipped. */
            assertNull("$firmware: device must not be pinned", payload.device)
            assertNull("$firmware: fingerprint must not be pinned", payload.fingerprint)
        }
    }

    @Test
    fun `shared artifacts are referenced, not duplicated, and every byte matches`() {
        val catalog = catalog()
        val shared = catalog.payloads.first().artifacts.filter { it.path.contains("/shared/") }
        assertEquals("shared set size", 7, shared.size)
        for (payload in catalog.payloads) {
            for (artifact in payload.artifacts) {
                val file = File(assets, artifact.path)
                assertTrue("missing ${artifact.path}", file.isFile)
                assertEquals(
                    "${payload.id}/${artifact.name}: size",
                    artifact.size,
                    file.length(),
                )
                assertEquals(
                    "${payload.id}/${artifact.name}: sha256",
                    artifact.sha256,
                    H80gtPayloadCatalog.sha256(file),
                )
                if (artifact.role == "exploit") {
                    assertTrue(
                        "${payload.id}: exploit must be payload-owned",
                        artifact.path.contains("/payload/"),
                    )
                }
            }
        }
        /* The APK stores the module once, not once per firmware: 7 shared
         * artifacts plus 6 per-firmware exploits, no duplicates. */
        val paths = catalog.payloads.flatMap { it.artifacts }.map { it.path }.distinct()
        assertEquals("distinct asset paths", 13, paths.size)
        assertEquals("shared assets", 7, paths.count { it.contains("/shared/") })
        assertEquals("per-firmware exploits", 6, paths.count { it.contains("/payload/") })
    }

    @Test
    fun `the staged loader template is the shipped LF file`() {
        val payload = catalog().payload("annap-AGT-AN00_9.0.0.230")!!
        val template = File(assets, payload.loaderTemplate!!.path).readText()
        assertTrue("template contains CR (CRLF checkout)", !template.contains('\r'))
        assertTrue("template lost its placeholders", template.contains("@RUNDIR@"))
        assertEquals(
            "608a079aa86f50e539c70b52a1f15818fc35db82a97bdd1c5a766f5fce79aa1f",
            H80gtPayloadCatalog.sha256(template.toByteArray()),
        )
    }

    @Test
    fun `an unknown payload id resolves to nothing`() {
        assertNull(catalog().payload("does-not-exist"))
    }

    @Test
    fun `a malformed catalogue is rejected`() {
        val cases = listOf(
            "schema_version = 1\n",
            "schema_version = 2\n",
            "schema_version = 2\npayloads = [ { id = \"x\" } ]\n",
            "schema_version = 2\npayloads = []\n",
        )
        for (text in cases) {
            val failed = runCatching { H80gtPayloadCatalog.parse(text) }.isFailure
            assertTrue("accepted an invalid catalogue: $text", failed)
        }
    }
}
