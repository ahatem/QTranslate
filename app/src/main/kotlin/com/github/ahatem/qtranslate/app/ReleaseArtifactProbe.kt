package com.github.ahatem.qtranslate.app

import com.github.ahatem.qtranslate.core.plugin.PluginStatus
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.SettingsRepository
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private object ProbeLocation

/** Readiness check launched from an extracted release distribution in CI. */
fun main(args: Array<String>) = runBlocking { runReleaseArtifactProbe(args) }

/** Also invoked through the packaged launcher, before the desktop UI starts. */
suspend fun runReleaseArtifactProbe(args: Array<String>) {
    require(args.size == 1) { "Expected extracted QTranslate directory" }
    val distribution = File(args[0]).canonicalFile
    val expectedIds = File(distribution, "portable-plugin-ids.txt").readLines().filter(String::isNotBlank).toSet()
    require(expectedIds.isNotEmpty()) { "Bundled plugin inventory is empty" }
    val portableJar = File(distribution, "QTranslate.jar")
    val packagedJar = File(distribution, "app/QTranslate.jar")
    val appJar = when {
        portableJar.isFile -> portableJar
        packagedJar.isFile -> packagedJar
        else -> error("QTranslate.jar is missing from the release distribution")
    }.canonicalFile
    val runningJar = File(ProbeLocation::class.java.protectionDomain.codeSource.location.toURI()).canonicalFile
    check(appJar == runningJar) { "Readiness check must run from the extracted QTranslate.jar" }
    val focusRequested = CountDownLatch(1)
    check(SingleInstanceGuard.tryLock { focusRequested.countDown() }) { "Could not acquire packaged instance lock" }
    try {
        check(!SingleInstanceGuard.tryLock { }) { "Second instance was not rejected" }
        check(focusRequested.await(5, TimeUnit.SECONDS)) { "Second instance did not hand off focus" }
    } finally {
        SingleInstanceGuard.release()
    }
    check(File(distribution, "languages").isDirectory) { "Bundled languages directory is missing" }
    check(File(distribution, "themes").isDirectory) { "Bundled themes directory is missing" }
    val pluginJars = File(distribution, "plugins").listFiles { file -> file.isFile && file.extension == "jar" }
        ?.toList().orEmpty()
    check(pluginJars.size == expectedIds.size) {
        "Expected ${expectedIds.size} bundled plugin JARs, found ${pluginJars.size}"
    }

    // Proves the two published shapes are distinguishable, and that a portable one keeps its data
    // with itself while a marker-free one — the future installer's payload — does not.
    //
    // The marker is the only declaration of portability, so it is what gets asserted, and the
    // resolved mode has to follow from it alone. That is the invariant which keeps an installer built
    // from this image from inheriting portable semantics and writing user data into Program Files.
    val marker = File(distribution, AppDataLayout.PORTABLE_MARKER)

    // The packaged launcher reports the distribution root through jpackage.app-path. Set it so this
    // probe resolves the root the same way the real launcher does — the code source alone points at
    // app/, which is not where a marker lives.
    // Resolved from the distribution's own marker, with the code source pointed at the distribution
    // root — which is what the packaged launcher reports through jpackage.app-path. Without this the
    // JAR's own parent is used, and in a packaged layout that is app/, where no marker lives.
    val launcher = File(distribution, "QTranslate.exe").takeIf { it.isFile }
    System.setProperty(
        AppDataLayout.LAUNCHER_PATH_PROPERTY,
        (launcher ?: File(distribution, "QTranslate.jar")).absolutePath
    )
    System.clearProperty(AppDataLayout.EXPLICIT_DATA_DIR_PROPERTY)

    val declared = AppDataLayout.resolve(
        AppDataLayout.Environment(
            launcherPath = System.getProperty(AppDataLayout.LAUNCHER_PATH_PROPERTY),
            codeSource = distribution,
            developmentBuild = false,
            osName = System.getProperty("os.name").orEmpty(),
            userHome = System.getProperty("user.home").orEmpty(),
            environment = { System.getenv(it) }
        ),
        AppDataLayout.KEEP_INSTALLED_DATA
    )
    if (marker.isFile) {
        check(declared.mode == AppDataMode.PORTABLE) {
            "A distribution carrying ${AppDataLayout.PORTABLE_MARKER} must resolve as PORTABLE, " +
                "not ${declared.mode}"
        }
        check(declared.userDataRoot.canonicalFile == distribution.canonicalFile) {
            "A portable distribution must keep its data in ${distribution.absolutePath}, " +
                "but resolved ${declared.userDataRoot.absolutePath}"
        }
    } else {
        check(declared.mode != AppDataMode.PORTABLE) {
            "A distribution without ${AppDataLayout.PORTABLE_MARKER} must not resolve as PORTABLE"
        }
        check(declared.userDataRoot.canonicalFile != distribution.canonicalFile) {
            "An installed copy must keep its data out of ${distribution.absolutePath}, " +
                "which is where the installer will place it"
        }
    }

    // A canary for this probe's own preconditions. CI extracts a pristine distribution, so anything
    // that looks like pre-marker user data here means the fixture is wrong rather than that a real
    // migration is needed — and a silent adoption here would let a packaging regression slip past.
    check(!declared.adoptedLegacyPortableData) {
        "${distribution.absolutePath} already contains pre-marker user state, so this probe cannot " +
            "assert a clean distribution shape"
    }

    // The probe then isolates itself into the extracted copy so it writes nothing real.
    System.clearProperty(AppDataLayout.LAUNCHER_PATH_PROPERTY)
    System.setProperty(AppDataLayout.EXPLICIT_DATA_DIR_PROPERTY, distribution.absolutePath)
    val layout = AppDataLayout.resolveForCurrentProcess(AppDataLayout.KEEP_INSTALLED_DATA)
    check(layout.mode == AppDataMode.CUSTOM) { "An explicit data directory must resolve as CUSTOM" }
    check(layout.userDataRoot.canonicalFile == distribution.canonicalFile) {
        "Explicit app data directory was not honoured: ${layout.userDataRoot.absolutePath}"
    }
    val loggerFactory = ConsoleLoggerFactory(ConsoleLoggerFactory.LogLevel.INFO)
    val settings = SettingsRepository(
        distribution,
        Json { ignoreUnknownKeys = true; isLenient = true },
        loggerFactory.getLogger("ReleaseArtifactProbe")
    )
    val probeConfig = Configuration.DEFAULT.copy(autoCheckForUpdates = false)
    check(settings.updateConfiguration(probeConfig).isOk) { "Could not initialize isolated readiness settings" }
    val dependencies = buildDependencies(
        appData = distribution,
        loggerFactory = loggerFactory,
        settingsRepo = settings,
        initialConfig = probeConfig,
        installationRoot = distribution
    )
    try {
        withTimeout(30_000) { dependencies.pluginManager.loadAndProcessPlugins() }
        val plugins = dependencies.pluginManager.plugins.value
        check(plugins.map { it.id }.toSet() == expectedIds && plugins.size == expectedIds.size) {
            "Packaged plugins differ from release inventory: expected $expectedIds, found ${plugins.map { it.id }}"
        }
        check(plugins.all { it.status == PluginStatus.ENABLED }) {
            "Plugin initialization failed: ${plugins.filter { it.status != PluginStatus.ENABLED }.map { "${it.id}: ${it.lastError}" }}"
        }
        Class.forName("javazoom.jl.player.Player")
        Class.forName("javazoom.jl.decoder.Bitstream")
        println("Release readiness passed: ${plugins.size} plugins and JLayer loaded on Java ${Runtime.version().feature()}")
    } finally {
        try {
            dependencies.pluginManager.shutdown()
        } finally {
            dependencies.mainStore.onShutdown()
            dependencies.httpClient.close()
            dependencies.appScope.cancel()
        }
    }
}
