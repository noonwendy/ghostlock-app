package com.ghostlock.app.data.h80gt

import com.ghostlock.app.data.HoconSupport
import com.ghostlock.app.data.ValueMap
import com.ghostlock.app.data.asValueList
import com.ghostlock.app.data.asValueMap
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * One file the specialized chain needs. `path` is the asset path inside the
 * APK; `name` is the file name created in the run directory, so the catalog and
 * the loader script can never disagree about where a payload lands.
 */
data class H80gtArtifact(
    val name: String,
    val path: String,
    val role: String,
    val sha256: String,
    val size: Long,
    val executable: Boolean,
    /** True for the loader template: rendered, then staged as [name]. */
    val render: Boolean,
    val license: String,
    val source: String,
)

/**
 * One exact-target payload set described by `assets/h80gt/payloads.conf`.
 *
 * Only the exploit is per firmware; the KernelSU module, the userspace tools
 * and the loader template come from the manifest's shared section, so a payload
 * carries no duplicate bytes.
 */
data class H80gtPayload(
    val id: String,
    val release: String,
    val firmware: String,
    val status: String,
    val source: String,
    val bundleSha256: String,
    val managerPackage: String,
    val managerVersion: String,
    /** Payload-owned artifacts plus the shared set. */
    val artifacts: List<H80gtArtifact>,
    /** Compared only when declared; see [com.ghostlock.app.data.profile.H80gtSpec]. */
    val device: String? = null,
    /** Compared only when declared. */
    val fingerprint: String? = null,
) {
    /** Artifacts staged verbatim (the loader template is rendered instead). */
    val staged: List<H80gtArtifact> get() = artifacts.filterNot { it.render }

    val loaderTemplate: H80gtArtifact?
        get() = artifacts.firstOrNull { it.render }

    val roles: Set<String> get() = artifacts.mapTo(linkedSetOf()) { it.role }

    fun artifact(name: String): H80gtArtifact? = artifacts.firstOrNull { it.name == name }

    /** Required roles; a payload missing one of them is incomplete. */
    fun missingRoles(): List<String> = RequiredRoles.filterNot { it in roles }

    companion object {
        /** Roles the chain cannot run without. */
        val RequiredRoles = listOf(
            "exploit", "module", "loader", "kmsg", "policy_tool", "policy_rules",
            "userspace", "loader_template",
        )
    }
}

/**
 * The bundled payload catalogue. It is the single authority for payload
 * identity, size and hash; the app and the Shizuku UserService both read the
 * same asset from the same APK and verify every staged byte against it.
 */
class H80gtPayloadCatalog(val payloads: List<H80gtPayload>) {
    fun payload(id: String): H80gtPayload? = payloads.firstOrNull { it.id == id }

    companion object {
        const val ManifestAsset = "h80gt/payloads.conf"

        /** v2 splits the firmware-independent artifacts into `shared`. */
        private const val SchemaVersion = 2

        /** Parses the manifest text; throws on a structurally invalid document. */
        fun parse(text: String): H80gtPayloadCatalog {
            val root = HoconSupport.parseValue(text).asValueMap()
                ?: error("payloads.conf is not an object")
            require((root["schema_version"] as? Number)?.toInt() == SchemaVersion) {
                "unsupported payloads.conf schema"
            }
            val shared = root["shared"].asValueMap()?.get("artifacts").asValueList()
                ?.mapIndexed { index, raw ->
                    parseArtifact(
                        raw.asValueMap() ?: error("shared.artifacts[$index] is not an object"),
                        "shared.artifacts[$index]",
                    )
                }
                .orEmpty()
            require(shared.map { it.name }.distinct().size == shared.size) {
                "payloads.conf declares a duplicate shared artifact name"
            }
            val entries = root["payloads"].asValueList()
                ?: error("payloads.conf has no payloads list")
            require(entries.isNotEmpty()) { "payloads.conf declares no payload" }
            val payloads = entries.mapIndexed { index, raw ->
                val entry = raw.asValueMap() ?: error("payloads[$index] is not an object")
                parsePayload(entry, index, shared)
            }
            require(payloads.map { it.id }.distinct().size == payloads.size) {
                "payloads.conf declares a duplicate payload id"
            }
            require(payloads.map { it.release }.distinct().size == payloads.size) {
                "payloads.conf declares a duplicate release"
            }
            return H80gtPayloadCatalog(payloads)
        }

        private fun parsePayload(
            entry: ValueMap,
            index: Int,
            shared: List<H80gtArtifact>,
        ): H80gtPayload {
            fun text(key: String): String =
                (entry[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                    ?: error("payloads[$index].$key is missing")
            val manager = entry["manager"].asValueMap()
                ?: error("payloads[$index].manager is missing")
            val own = entry["artifacts"].asValueList()
                ?.mapIndexed { artifactIndex, raw ->
                    parseArtifact(
                        raw.asValueMap()
                            ?: error("payloads[$index].artifacts[$artifactIndex] is not an object"),
                        "payloads[$index].artifacts[$artifactIndex]",
                    )
                }
                .orEmpty()
            val merged = own + shared
            require(merged.map { it.name }.distinct().size == merged.size) {
                "payloads[$index] declares a duplicate artifact name"
            }
            require(own.any { it.role == "exploit" }) {
                "payloads[$index] must own its exploit artifact"
            }
            return H80gtPayload(
                id = text("id"),
                release = text("release"),
                firmware = text("firmware"),
                status = text("status"),
                source = text("source"),
                bundleSha256 = text("bundle_sha256"),
                managerPackage = text(manager, "package", "payloads[$index].manager"),
                managerVersion = text(manager, "version", "payloads[$index].manager"),
                artifacts = merged,
                device = optionalText(entry, "device"),
                fingerprint = optionalText(entry, "fingerprint"),
            )
        }

        private fun parseArtifact(artifact: ValueMap, where: String): H80gtArtifact =
            H80gtArtifact(
                name = text(artifact, "name", where),
                path = text(artifact, "path", where),
                role = text(artifact, "role", where),
                sha256 = text(artifact, "sha256", where).lowercase(),
                size = (artifact["size"] as? Number)?.toLong()
                    ?: error("$where.size is missing"),
                executable = artifact["executable"] == true,
                render = artifact["render"] == true,
                license = text(artifact, "license", where),
                source = text(artifact, "source", where),
            )

        private fun text(map: Map<*, *>, key: String, where: String): String =
            (map[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                ?: error("$where.$key is missing")

        private fun optionalText(map: Map<*, *>, key: String): String? =
            (map[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }

        /** Reads the catalogue out of the installed APK. */
        fun fromApk(apk: File): H80gtPayloadCatalog {
            val text = readAsset(apk, ManifestAsset)
                ?: error("${apk.name} does not carry $ManifestAsset")
            return parse(text)
        }

        /** One asset out of the APK, or null when it is not packaged. */
        fun readAsset(apk: File, path: String): String? =
            readAssetBytes(apk, path)?.toString(Charsets.UTF_8)

        fun readAssetBytes(apk: File, path: String): ByteArray? {
            if (!apk.isFile) return null
            return runCatching {
                ZipFile(apk).use { zip ->
                    val entry = zip.getEntry("assets/$path") ?: return@use null
                    zip.getInputStream(entry).use(InputStream::readBytes)
                }
            }.getOrNull()
        }

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }

        fun sha256(file: File): String =
            MessageDigest.getInstance("SHA-256").run {
                file.inputStream().use { stream ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = stream.read(buffer)
                        if (read <= 0) break
                        update(buffer, 0, read)
                    }
                }
                digest().joinToString("") { "%02x".format(it) }
            }
    }
}
