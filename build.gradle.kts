import java.util.Properties

plugins {
    id("com.android.application") version "9.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
    id("org.jetbrains.kotlin.jvm") version "2.4.20" apply false
}

private fun localProperties(): Properties = Properties().also { properties ->
    val propertiesFile = rootProject.file("local.properties")
    if (propertiesFile.isFile) {
        propertiesFile.inputStream().use(properties::load)
    }
}

private fun ondkHome(): String? =
    System.getenv("ONDK_HOME")?.takeIf(String::isNotBlank)
        ?: localProperties().getProperty("ondk.dir")?.takeIf(String::isNotBlank)

private fun useOndk(): Boolean = !ondkHome().isNullOrBlank()

private fun resolveNdkDir(): String {
    val ondk = ondkHome()
    if (ondk != null) return ondk

    val properties = localProperties()
    val ndkEnvironment = System.getenv("ANDROID_NDK_HOME")
        ?: System.getenv("ANDROID_NDK_ROOT")
    if (!ndkEnvironment.isNullOrBlank()) return ndkEnvironment

    properties.getProperty("ndk.dir")?.takeIf(String::isNotBlank)?.let { return it }

    val sdkDir = properties.getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME")
    if (!sdkDir.isNullOrBlank()) {
        val ndkRoot = File(sdkDir, "ndk")
        val versions = ndkRoot.listFiles()
            ?.filter(File::isDirectory)
            ?.map(File::getName)
            ?.sorted()
            .orEmpty()
        if (versions.isNotEmpty()) return File(ndkRoot, versions.last()).absolutePath
    }

    throw GradleException("NDK not found; set ANDROID_NDK_HOME or ndk.dir in local.properties")
}

private data class NdkTools(val clang: String, val ar: String)

private fun resolveCargoExecutable(): String {
    val cargoOnPath = System.getenv("PATH")
        .orEmpty()
        .split(File.pathSeparator)
        .asSequence()
        .map { File(it, "cargo") }
        .firstOrNull { it.isFile && it.canExecute() }
    if (cargoOnPath != null) return cargoOnPath.absolutePath

    val cargoInRustupHome = File(System.getProperty("user.home"), ".cargo/bin/cargo")
    return if (cargoInRustupHome.isFile && cargoInRustupHome.canExecute()) {
        cargoInRustupHome.absolutePath
    } else {
        "cargo"
    }
}

private fun extractNdkTools(): NdkTools {
    val ndk = resolveNdkDir()
    val osName = System.getProperty("os.name").lowercase()
    val isWindows = osName.contains("windows")
    val prebuilt = when {
        isWindows -> "windows-x86_64"
        osName.contains("mac") -> "darwin-x86_64"
        else -> "linux-x86_64"
    }
    val binDir = File(ndk, "toolchains/llvm/prebuilt/$prebuilt/bin")
    return NdkTools(
        clang = File(
            binDir,
            if (isWindows) "aarch64-linux-android34-clang.cmd" else "aarch64-linux-android34-clang",
        ).absolutePath,
        ar = File(binDir, if (isWindows) "llvm-ar.exe" else "llvm-ar").absolutePath,
    )
}

// Every module's output lives under the root build/ directory (native,
// host-test, extract, kernel-profiles, app). Delete the whole tree here so a
// single root `clean` resets all of them.
tasks.register<Delete>("clean") {
    description = "Delete the root build/ directory (all module outputs)."
    delete(layout.buildDirectory)
}

tasks.register<Exec>("buildGhostlockNative") {
    description = "buildGhostlockNative"
    workingDir(file("src"))
    commandLine("make", "ghostlock")
    val ndk = resolveNdkDir()
    environment("ANDROID_NDK_HOME", ndk)
    environment("NDK_ROOT", ndk)
    inputs.files(
        fileTree("src") { include("**/*.c", "**/*.h", "**/*.cpp", "**/*.hpp") },
        file("src/Makefile"),
    )
    outputs.file(file("build/native/ghostlock"))
}

tasks.register<Copy>("prepareGhostlockJniLibs") {
    description = "prepareGhostlockJniLibs"
    dependsOn("buildGhostlockNative")
    from("build/native/ghostlock")
    into("app/src/main/jniLibs/arm64-v8a")
    rename { "libghostlock.so" }
    /* Strip only the packaged copy: static libc++ carries its DWARF into the
     * binary, while the top-level ghostlock keeps its symbols for the
     * disassembly comparisons. Paths are captured as plain strings so the
     * configuration cache can serialize this task. */
    val stripPath = File(extractNdkTools().clang)
        .resolveSibling("llvm-strip").absolutePath
    val packagedPath = File(rootDir, "app/src/main/jniLibs/arm64-v8a/libghostlock.so").absolutePath
    doLast {
        val code = ProcessBuilder(stripPath, "--strip-all", packagedPath)
            .inheritIO()
            .start()
            .waitFor()
        check(code == 0) { "llvm-strip failed with $code" }
    }
}

tasks.register<Exec>("buildGhostlockExtract") {
    description = "buildGhostlockExtract"
    val tools = extractNdkTools()
    val isOndk = useOndk()
    val command = mutableListOf(resolveCargoExecutable())
    if (isOndk) command += "+ondk"
    command += listOf("build", "--release", "--target", "aarch64-linux-android")
    if (isOndk) {
        command += listOf("-Z", "build-std=std,panic_abort")
        command += listOf("-Z", "build-std-features=optimize_for_size")
    }
    workingDir(rootProject.file("tools/extract_rs"))
    commandLine(command)
    environment("CC_aarch64_linux_android", tools.clang)
    environment("AR_aarch64_linux_android", tools.ar)
    environment("CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER", tools.clang)
    environment("RUSTFLAGS", "-C force-unwind-tables=no -C link-arg=-Wl,--icf=all")
    if (isOndk) environment("RUSTC_BOOTSTRAP", "1")
    inputs.files(
        fileTree("tools/extract_rs/src") { include("**/*.rs") },
        file("tools/extract_rs/Cargo.toml"),
        file("tools/extract_rs/Cargo.lock"),
    )
    inputs.property("useOndk", isOndk)
    outputs.file(file("build/extract/aarch64-linux-android/release/ghostlock-extract"))
}

tasks.register<Copy>("prepareGhostlockExtractJniLibs") {
    description = "prepareGhostlockExtractJniLibs"
    dependsOn("buildGhostlockExtract")
    from("build/extract/aarch64-linux-android/release/ghostlock-extract")
    into("app/src/main/jniLibs/arm64-v8a")
    rename { "libextract.so" }
}

/* ---- H80GT specialized payload (engine = "h80gt") -------------------------
 * The v0.3.1 release bundle is the source of truth; the bytes are staged into
 * app/src/main/assets/h80gt/ (never committed) and every file is hash-verified
 * against assets/h80gt/payloads.conf by :profile-core:verifyH80gtPayload.
 *
 * Only the exploit is per firmware; the firmware-independent set is staged once
 * under shared/. Selection is by kernel string, as in GhostLock-H80GT's own
 * manifest, so no device-unique data is staged or compared.
 *
 *   -Pghostlock.h80gt=<dir>  directory containing `ghostlock/` (default ../release/bundle)
 *   -Pghostlock.h80gt=off    stage nothing; the app refuses engine = h80gt at preflight
 */
val h80gtFirmwares = listOf(
    "8.0.0.128",
    "8.0.0.160",
    "9.0.0.157",
    "9.0.0.200SP1",
    "9.0.0.220SP2",
    "9.0.0.230",
)
val h80gtSharedSources = linkedMapOf(
    "kernelsu.ko" to "kernelsu.ko",
    "load_ko" to "load_ko",
    "kmsg_dumper" to "kmsg_dumper",
    "magiskpolicy" to "magiskpolicy",
    "ksud" to "ksud",
    "ksu_rules" to "ksu_rules",
    "ksu_loader.tmpl" to "ksu_loader.tmpl",
)
val h80gtDisabled: Boolean =
    (findProperty("ghostlock.h80gt") as String?)?.equals("off", ignoreCase = true) == true
val h80gtBundleDir: File? = run {
    if (h80gtDisabled) return@run null
    (findProperty("ghostlock.h80gt") as String?)?.takeIf { it.isNotBlank() }?.let { return@run File(it) }
    /* The fork ships patched exploits: prefer the regenerated bundle when the
     * workspace has one, fall back to the pinned upstream bundle. */
    File(rootDir.parentFile, "release/bundle-patched").takeIf { it.isDirectory }
        ?: File(rootDir.parentFile, "release/bundle").takeIf { it.isDirectory }
}
val h80gtAssetsDir = layout.projectDirectory.dir("app/src/main/assets/h80gt").asFile
/* Fork-built exploits are committed; the shared set still comes from the bundle. */
val h80gtCommittedExploitsDir = layout.projectDirectory.dir("h80gt/exploits").asFile

tasks.register("prepareH80gtPayload") {
    description = "Stage the H80GT specialized payload from the pinned release bundle"
    /* Every value the action reads is captured as a plain local: the
     * configuration cache cannot serialise references to the build script. */
    val disabled = h80gtDisabled
    val bundle = h80gtBundleDir
    val assets = h80gtAssetsDir
    val committedExploits = h80gtCommittedExploitsDir
    val firmwares = h80gtFirmwares.toList()
    val sharedSources = LinkedHashMap(h80gtSharedSources)
    inputs.property("disabled", disabled)
    inputs.property("firmwares", firmwares.size)
    inputs.file(File(assets, "payloads.conf")).optional()
    if (bundle != null) inputs.dir(File(bundle, "ghostlock"))
    inputs.dir(committedExploits)
    outputs.dir(assets)
    doLast {
        val payloadRoot = File(assets, "payload")
        val sharedDir = File(assets, "shared")
        /* Text artifacts must be LF: a CRLF checkout makes /system/bin/sh
         * reject the rendered loader. */
        val textNames = setOf("ksu_rules", "ksu_loader.tmpl")
        fun stage(source: File, target: File) {
            target.parentFile?.mkdirs()
            if (source.name in textNames) {
                target.writeText(source.readText().replace("\r\n", "\n"))
            } else {
                source.copyTo(target, overwrite = true)
            }
        }
        payloadRoot.deleteRecursively()
        sharedDir.deleteRecursively()
        /* Legacy single-payload layout from batch 1: never ship two copies. */
        File(assets, "ksu_loader.tmpl").delete()
        if (disabled) {
            logger.lifecycle("prepareH80gtPayload: disabled (-Pghostlock.h80gt=off); engine=h80gt will refuse")
            return@doLast
        }
        require(bundle != null) {
            "H80GT payload bundle not found. Pass -Pghostlock.h80gt=<dir containing ghostlock/> " +
                "(default ../release/bundle) or -Pghostlock.h80gt=off."
        }
        val root = File(bundle, "ghostlock")
        require(root.isDirectory) { "not an H80GT release bundle (missing ghostlock/): $root" }
        for ((sourceName, targetName) in sharedSources) {
            val source = File(root, sourceName)
            require(source.isFile) { "missing $sourceName in the H80GT bundle ($root)" }
            stage(source, File(sharedDir, targetName))
            logger.lifecycle("prepareH80gtPayload: $sourceName -> ${File(sharedDir, targetName).absolutePath}")
        }
        for (firmware in firmwares) {
            /* The fork rebuilds these six exploits; they are committed and take
             * precedence over the bundle's copies. The shared set has no
             * committed copy and still comes from the pinned bundle. */
            val committed = File(committedExploits, "$firmware/exploit")
            val source = if (committed.isFile) committed else File(root, "exploits/$firmware/exploit")
            require(source.isFile) {
                "missing h80gt/exploits/$firmware/exploit (committed) and " +
                    "exploits/$firmware/exploit in the H80GT bundle ($root)"
            }
            val target = File(payloadRoot, "annap-AGT-AN00_$firmware/exploit")
            stage(source, target)
            logger.lifecycle("prepareH80gtPayload: ${source.path} -> ${target.absolutePath}")
        }
    }
}
