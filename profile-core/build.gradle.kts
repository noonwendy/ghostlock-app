@file:Suppress("UnstableApiUsage")

plugins {
    id("org.jetbrains.kotlin.jvm")
}

// Keep this module's output under the repository-root build/ directory.
layout.buildDirectory.set(rootProject.layout.buildDirectory.dir("profile-core"))

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation("com.typesafe:config:1.4.3")
    testImplementation("junit:junit:4.13.2")
}

/**
 * Serializes every bundled HOCON kernel profile into the v2 binary layout the
 * native side reads. The exporter runs the same [ProfileMerger] /
 * [ProfileResolver] / `NativeProfileDocument` code as the app.
 */
tasks.register<JavaExec>("exportKernelProfiles") {
    description = "Serialize bundled HOCON kernel profiles into v2 .bin documents"
    group = "build"
    val profilesDir = rootProject.layout.projectDirectory.dir("app/src/main/assets/kernel_profiles")
    val outputDir = rootProject.layout.buildDirectory.dir("kernel-profiles")
    inputs.dir(profilesDir)
    outputs.dir(outputDir)
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.ghostlock.app.data.profile.ProfileExporter")
    args(
        profilesDir.asFile.absolutePath,
        outputDir.get().asFile.absolutePath,
        outputDir.get().asFile.absolutePath,
    )
}

/**
 * Verifies the staged H80GT payload against `assets/h80gt/payloads.conf` with
 * the same parser the app and the Shizuku UserService use. It runs the staging
 * task first, so a stale, truncated or missing bundle fails the build instead
 * of producing an APK whose every run would be refused at preflight.
 */
tasks.register<JavaExec>("verifyH80gtPayload") {
    description = "Verify the staged H80GT payload (size + SHA-256) against payloads.conf"
    group = "verification"
    val assetsDir = rootProject.layout.projectDirectory.dir("app/src/main/assets")
    val allowMissing = (rootProject.findProperty("ghostlock.h80gt") as String?)
        ?.equals("off", ignoreCase = true) == true
    dependsOn(rootProject.tasks.named("prepareH80gtPayload"))
    inputs.dir(assetsDir.dir("h80gt"))
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.ghostlock.app.data.h80gt.H80gtPayloadVerifier")
    args(assetsDir.asFile.absolutePath)
    if (allowMissing) args("--allow-missing")
}
