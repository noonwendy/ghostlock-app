package com.ghostlock.app.data.h80gt

import java.io.File

/**
 * Build-time verification of the staged H80GT payload.
 *
 * `assets/h80gt/payloads.conf` is the authority; this runs the same parser the
 * app and the Shizuku UserService use, so a stale bundle, a truncated file or a
 * payload that was never staged fails the build instead of shipping an APK that
 * would refuse every run at preflight.
 *
 * Usage: `H80gtPayloadVerifier <assetsDir> [--allow-missing]`
 * `--allow-missing` is for `-Pghostlock.h80gt=off` builds, where the app is
 * expected to refuse `engine = h80gt` at runtime.
 */
object H80gtPayloadVerifier {
    @JvmStatic
    fun main(args: Array<String>) {
        val assetsDir = File(args.firstOrNull() ?: error("usage: <assetsDir> [--allow-missing]"))
        val allowMissing = args.drop(1).any { it == "--allow-missing" }
        val manifest = File(assetsDir, H80gtPayloadCatalog.ManifestAsset)
        require(manifest.isFile) { "missing ${manifest.absolutePath}" }
        val catalog = H80gtPayloadCatalog.parse(manifest.readText())
        /* Shared artifacts are listed by every payload; hash them once. */
        val unique = catalog.payloads
            .flatMap { it.artifacts }
            .distinctBy { it.path }
            .sortedBy { it.path }
        var failures = 0
        var verified = 0
        for (artifact in unique) {
            val file = File(assetsDir, artifact.path)
            if (!file.isFile) {
                if (allowMissing) {
                    println("h80gt payload: ${artifact.path} not staged (payload disabled)")
                    continue
                }
                println("h80gt payload: MISSING ${artifact.path}")
                failures++
                continue
            }
            val size = file.length()
            val digest = H80gtPayloadCatalog.sha256(file)
            if (size != artifact.size || digest != artifact.sha256) {
                println(
                    "h80gt payload: MISMATCH ${artifact.path} " +
                        "(size $size/${artifact.size}, sha256 $digest/${artifact.sha256})",
                )
                failures++
            } else {
                verified++
                println("h80gt payload: ok ${artifact.path} ($size bytes)")
            }
        }
        check(failures == 0) { "$failures H80GT payload artifact(s) failed verification" }
        println(
            "h80gt payload: ${catalog.payloads.size} payload(s), $verified unique artifact(s) verified",
        )
    }
}
