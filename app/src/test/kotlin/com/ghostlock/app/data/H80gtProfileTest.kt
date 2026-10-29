package com.ghostlock.app.data

import android.app.Application
import androidx.core.content.edit
import com.ghostlock.app.domain.model.CpuPair
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.nio.file.Files

/**
 * V1: the shipped H80GT profile resolves, is selectable through both the
 * builtin and the imported path, reports the engine that will actually run, and
 * can never produce a native document.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class H80gtProfileTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val pair = CpuPair(primary = 0, consumer = 1)
    private val release = "5.10.236-android12-9-gd671cf8c2b02"

    private fun newController(root: java.io.File, preferencesName: String): AndroidProfileConfigController =
        AndroidProfileConfigController(
            context = context,
            filesDir = root,
            userProfiles = UserProfileStore(
                directory = root.resolve("user_profiles"),
                assetLoader = AssetConfigLoader(context),
            ),
            preferences = context.getSharedPreferences(preferencesName, 0)
                .also { it.edit().clear().commit() },
        )

    @Test
    fun `the bundled profile resolves to the specialized engine`() = runBlocking {
        val root = Files.createTempDirectory("h80gt-profile").toFile()
        try {
            val controller = newController(root, "h80gt-profile")
            val config = controller.load(release, pair)
            assertTrue("profile did not resolve", config.hasProfile)
            assertEquals("h80gt", config.engine)
            assertEquals(emptySet<String>(), config.invalidPaths)
            val spec = config.h80gt
            assertNotNull("h80gt section missing", spec)
            assertEquals("annap-AGT-AN00_9.0.0.230", spec!!.payload)
            assertEquals("9.0.0.230", spec.firmware)
            assertEquals("3.3.0", spec.managerVersion)
            assertEquals(release, spec.release)
            /* Selection is by kernel string: no device-unique data is shipped. */
            assertNull("profile must not pin the model", spec.device)
            assertNull("profile must not pin the fingerprint", spec.fingerprint)
            assertNull(
                "specialized engine must not produce a GLK1 document",
                controller.nativeDocument(config),
            )
            assertEquals(emptyList<Any>(), config.general)
            /* Only the scalar target marker is left: no task/cred/offset/route field
             * may appear for a profile that carries none. */
            assertEquals(listOf("kernel_major"), config.roots.map { it.path })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `every bundled h80gt profile resolves to its own payload`() = runBlocking {
        val firmwareToRelease = linkedMapOf(
            "8.0.0.128" to "5.10.168-android12-9-g804a21a522f0",
            "8.0.0.160" to "5.10.209-android12-9-g516fa23f87fc",
            "9.0.0.157" to "5.10.209-android12-9-ga3ff7ae30334",
            "9.0.0.200SP1" to "5.10.236-android12-9-gd1575ab10b30",
            "9.0.0.220SP2" to "5.10.236-android12-9-ga88335e8db40",
            "9.0.0.230" to "5.10.236-android12-9-gd671cf8c2b02",
        )
        val root = Files.createTempDirectory("h80gt-all").toFile()
        try {
            val controller = newController(root, "h80gt-all")
            for ((firmware, kernel) in firmwareToRelease) {
                val config = controller.load(kernel, pair)
                assertEquals("$firmware: engine", "h80gt", config.engine)
                assertEquals("$firmware: invalid", emptySet<String>(), config.invalidPaths)
                assertEquals("$firmware: payload", "annap-AGT-AN00_$firmware", config.h80gt?.payload)
                assertEquals("$firmware: firmware", firmware, config.h80gt?.firmware)
                assertNull("$firmware: must not pin the model", config.h80gt?.device)
                assertNull("$firmware: must not pin the fingerprint", config.h80gt?.fingerprint)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `an imported copy of the profile selects the same engine`() = runBlocking {
        val root = Files.createTempDirectory("h80gt-import").toFile()
        try {
            val text = AssetConfigLoader(context).load("kernel_profiles/annap-agt-an00-9.0.0.230.conf")
            val store = UserProfileStore(
                directory = root.resolve("user_profiles"),
                assetLoader = AssetConfigLoader(context),
            )
            val name = store.save("h80gt-import.conf", text)
            val controller = AndroidProfileConfigController(
                context = context,
                filesDir = root,
                userProfiles = store,
                preferences = context.getSharedPreferences("h80gt-import", 0)
                    .also { it.edit().clear().commit() },
            )
            val config = controller.selectUserProfile(name, release, pair)
            assertEquals("h80gt", config.engine)
            assertEquals("annap-AGT-AN00_9.0.0.230", config.h80gt?.payload)
            assertEquals(
                "unexpected invalid paths",
                emptySet<String>(),
                config.invalidPaths,
            )
            /* The legacy normaliser must not infer a route or seed geometry into
             * a specialized profile, and such a document is already current. */
            assertEquals(2, store.list().single().version)
            val converted = HoconSupport.parseValue(text).asValueMap()
            LegacyProfileConverter.convertValue(converted)
            assertFalse("converter injected native fields", converted!!.containsKey("route"))
            assertFalse(converted.containsKey("cred"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `the profile fails loudly when the engine token is unknown`() = runBlocking {
        val root = Files.createTempDirectory("h80gt-bad-engine").toFile()
        try {
            val text = AssetConfigLoader(context)
                .load("kernel_profiles/annap-agt-an00-9.0.0.230.conf")
                .replace("engine = \"h80gt\"", "engine = \"h80gt-v9\"")
            val store = UserProfileStore(
                directory = root.resolve("user_profiles"),
                assetLoader = AssetConfigLoader(context),
            )
            val name = store.save("bad-engine.conf", text)
            val controller = AndroidProfileConfigController(
                context = context,
                filesDir = root,
                userProfiles = store,
                preferences = context.getSharedPreferences("h80gt-bad-engine", 0)
                    .also { it.edit().clear().commit() },
            )
            val config = controller.selectUserProfile(name, release, pair)
            assertTrue("unknown engine must be reported as an invalid field", config.invalidPaths.contains("engine"))
            assertNull(config.h80gt)
        } finally {
            root.deleteRecursively()
        }
    }
}
