plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlinPluginSerialization)
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(project(":api"))
    implementation(libs.kotlinxSerialization)
    implementation(libs.jna)

    testImplementation(kotlin("test"))
    testImplementation(testFixtures(project(":plugins:common")))
    testImplementation(libs.kotlinxCoroutines)
}

tasks.shadowJar {
    archiveBaseName.set("system-services-plugin")
    archiveClassifier.set("")
    archiveVersion.set("")
}

// Pinned because PackagedPluginJarTest opens this JAR by path.
tasks.named<Jar>("jar") {
    archiveBaseName.set("system-services")
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
    systemProperty("systemServices.pluginJar", layout.buildDirectory.file("libs/system-services.jar").get().asFile.absolutePath)
    systemProperty("systemServices.macHelperPackaged", macVisionHelperWillBePackaged.toString())
    systemProperty("systemServices.macSpellPackaged", (providers.gradleProperty("macSpellHelper").orNull != null || isMacHost).toString())
}

// The NSSpellChecker helper is built on macOS and included in packages assembled elsewhere.
val macSpellResource = "native/macos/system_spell"
val providedMacSpellHelper = providers.gradleProperty("macSpellHelper").orNull?.let { rootProject.file(it) }
val packageMacSpell = providedMacSpellHelper != null || isMacHost
val spellBuildDir = layout.buildDirectory.dir("generated/mac-spell-build")
val spellResourcesDir = layout.buildDirectory.dir("generated/mac-spell-resources")
val spellArm64 = spellBuildDir.map { it.file("system_spell-arm64") }
val spellX64 = spellBuildDir.map { it.file("system_spell-x86_64") }
val spellUniversal = spellBuildDir.map { it.file("system_spell") }

fun registerSpellCompile(name: String, target: String, output: Provider<RegularFile>): TaskProvider<Exec> {
    val source = layout.projectDirectory.file("src/main/resources/scripts/system_spell.swift")
    val destination = output.get().asFile
    return tasks.register(name, Exec::class.java) {
        group = "build"
        enabled = isMacHost && providedMacSpellHelper == null
        inputs.file(source)
        outputs.file(output)
        doFirst { destination.parentFile.mkdirs() }
        commandLine("swiftc", "-O", "-target", target, source.asFile.absolutePath, "-o", destination.absolutePath)
    }
}

val spellCompileArm64 = registerSpellCompile("compileMacSpellArm64", "arm64-apple-macos11", spellArm64)
val spellCompileX64 = registerSpellCompile("compileMacSpellX64", "x86_64-apple-macos11", spellX64)
val lipoMacSpell = tasks.register("lipoMacSpell", Exec::class.java) {
    group = "build"
    enabled = isMacHost && providedMacSpellHelper == null
    dependsOn(spellCompileArm64, spellCompileX64)
    inputs.files(spellArm64, spellX64)
    outputs.file(spellUniversal)
    val destination = spellUniversal.get().asFile
    doFirst { destination.parentFile.mkdirs() }
    commandLine("lipo", "-create", "-output", destination.absolutePath,
        spellArm64.get().asFile.absolutePath, spellX64.get().asFile.absolutePath)
}

tasks.register("buildMacSpellHelper") {
    group = "build"
    dependsOn(lipoMacSpell)
}

val prepareMacSpell = tasks.register("prepareMacSpellHelper", Copy::class.java) {
    enabled = packageMacSpell
    dependsOn(lipoMacSpell)
    when {
        providedMacSpellHelper != null -> from(providedMacSpellHelper)
        isMacHost -> from(spellUniversal)
    }
    into(spellResourcesDir.map { it.dir(macSpellResource.substringBeforeLast('/')) })
    val fileName = macSpellResource.substringAfterLast('/')
    rename { fileName }
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(prepareMacSpell)
    if (packageMacSpell) from(spellResourcesDir)
}
