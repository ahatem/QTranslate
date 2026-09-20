plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlinPluginSerialization)
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(project(":api"))
    implementation(libs.kotlinxSerialization)

    testImplementation(kotlin("test"))
    testImplementation(testFixtures(project(":plugins:common")))
    testImplementation(libs.kotlinxCoroutines)
}

tasks.shadowJar {
    archiveBaseName.set("system-ocr-plugin")
    archiveClassifier.set("")
    archiveVersion.set("")
}

// Pinned because PackagedPluginJarTest opens this JAR by path.
tasks.named<Jar>("jar") {
    archiveBaseName.set("system-ocr")
    archiveVersion.set("")
}

// The Vision helper is Swift, so it can only be compiled where a Swift toolchain exists: on a macOS
// host by `buildMacVisionHelper` (two swiftc runs plus lipo), or in the release pipeline, where a
// macOS job builds it and passes the binary on with -PmacVisionHelper=<file> for the Linux and
// Windows packaging jobs. It always lands at `native/macos/vision_ocr` in the JAR, as one universal
// arm64 + x86_64 binary, which is the path MAC_VISION_HELPER_RESOURCE names in the plugin.

val macVisionHelperResourcePath = "native/macos/vision_ocr"
val macVisionHelperResourceDir = macVisionHelperResourcePath.substringBeforeLast('/')
val macVisionHelperFileName = macVisionHelperResourcePath.substringAfterLast('/')
val isMacHost: Boolean = System.getProperty("os.name").lowercase().contains("mac")
val providedMacVisionHelper: File? = providers.gradleProperty("macVisionHelper").orNull?.let { rootProject.file(it) }
val macVisionHelperProvided: Boolean = providedMacVisionHelper != null
val macVisionHelperWillBePackaged: Boolean = macVisionHelperProvided || isMacHost

val macHelperResourcesDir: Provider<Directory> = layout.buildDirectory.dir("generated/mac-helper-resources")
val macHelperBuildDir: Provider<Directory> = layout.buildDirectory.dir("generated/mac-helper-build")

val macHelperArm64: Provider<RegularFile> = macHelperBuildDir.map { it.file("vision_ocr-arm64") }
val macHelperX64: Provider<RegularFile> = macHelperBuildDir.map { it.file("vision_ocr-x86_64") }
val macHelperUniversal: Provider<RegularFile> = macHelperBuildDir.map { it.file("vision_ocr") }

// Disabled rather than guarded with onlyIf, so no closure capturing build-script state is retained
// and the configuration cache stays valid.
fun registerSwiftCompileTask(name: String, target: String, output: Provider<RegularFile>): TaskProvider<Exec> {
    val source = layout.projectDirectory.file("src/main/resources/scripts/vision_ocr.swift")
    val outputFile = output.get().asFile
    val outputDirectory = outputFile.parentFile.absolutePath
    val buildOnThisHost = isMacHost && !macVisionHelperProvided

    return tasks.register(name, Exec::class.java) {
        group = "build"
        description = "Compiles the Apple Vision OCR helper for $target (macOS with Swift only)."
        enabled = buildOnThisHost
        inputs.file(source)
        outputs.file(output)
        doFirst { File(outputDirectory).mkdirs() }
        commandLine(
            "swiftc", "-O", "-target", target,
            source.asFile.absolutePath,
            "-o", outputFile.absolutePath,
        )
    }
}

val compileMacVisionHelperArm64 =
    registerSwiftCompileTask("compileMacVisionHelperArm64", "arm64-apple-macos11", macHelperArm64)
val compileMacVisionHelperX64 =
    registerSwiftCompileTask("compileMacVisionHelperX64", "x86_64-apple-macos11", macHelperX64)

val lipoMacVisionHelper = tasks.register("lipoMacVisionHelper", Exec::class.java) {
    group = "build"
    description = "Combines the arm64 and x86_64 Vision helpers into one universal binary."
    enabled = isMacHost && !macVisionHelperProvided
    dependsOn(compileMacVisionHelperArm64, compileMacVisionHelperX64)
    inputs.files(macHelperArm64, macHelperX64)
    outputs.file(macHelperUniversal)

    val universalFile = macHelperUniversal.get().asFile
    val universalDirectory = universalFile.parentFile.absolutePath
    val arm64Path = macHelperArm64.get().asFile.absolutePath
    val x64Path = macHelperX64.get().asFile.absolutePath

    doFirst { File(universalDirectory).mkdirs() }
    commandLine("lipo", "-create", "-output", universalFile.absolutePath, arm64Path, x64Path)
}

// Stable entry point for CI: builds the universal helper on a macOS runner.
val buildMacVisionHelper = tasks.register("buildMacVisionHelper") {
    group = "build"
    description = "Builds the universal Apple Vision OCR helper (macOS with a Swift toolchain only)."
    dependsOn(lipoMacVisionHelper)
}

// Stages the helper at its packaged resource path, from whichever source this build has.
val prepareMacVisionHelper = tasks.register("prepareMacVisionHelper", Copy::class.java) {
    group = "build"
    description = "Stages the macOS Vision helper at its packaged resource path."
    enabled = macVisionHelperWillBePackaged
    dependsOn(lipoMacVisionHelper)
    when {
        providedMacVisionHelper != null -> from(providedMacVisionHelper)
        isMacHost -> from(macHelperUniversal)
    }
    into(macHelperResourcesDir.map { it.dir(macVisionHelperResourceDir) })
    // Captured as a local so the rename closure holds a String rather than the build script.
    val fileName = macVisionHelperFileName
    rename { fileName }
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(prepareMacVisionHelper)
    // Only when this build has a helper, so a stale binary in build/ is never packaged.
    if (macVisionHelperWillBePackaged) {
        from(macHelperResourcesDir)
    }
}

tasks.named<Test>("test") {
    dependsOn(tasks.named("jar"))
    systemProperty("systemOcr.pluginJar", layout.buildDirectory.file("libs/system-ocr.jar").get().asFile.absolutePath)
    systemProperty("systemOcr.macHelperPackaged", macVisionHelperWillBePackaged.toString())
}
