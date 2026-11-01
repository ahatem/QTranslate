package com.github.ahatem.qtranslate.core.plugin

import com.github.ahatem.qtranslate.api.core.ApiVersion
import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.plugin.Plugin
import com.github.ahatem.qtranslate.api.plugin.PluginContext
import com.github.ahatem.qtranslate.api.plugin.PluginSettings
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

/**
 * Deterministic fixtures for plugin trust and lifecycle tests.
 *
 * The fixture plugins are compiled into the test classpath but packaged into real JARs, so they
 * travel the whole production path — assessment, snapshot, approval, construction in a plugin
 * class loader. Everything a test needs to observe crosses the class-loader boundary through
 * this file's objects, which resolve through parent delegation and are therefore shared
 * identities: the plugin loader only pins the *provider* class itself to the snapshot.
 */
internal object PluginTestFixtures {

    /** The service descriptor entry every fixture JAR carries. */
    const val PROVIDER_DESCRIPTOR = "META-INF/services/com.github.ahatem.qtranslate.api.plugin.Plugin"

    /** Records what the host actually did to a plugin, across class-loader boundaries. */
    object Log {
        private val events = CopyOnWriteArrayList<String>()
        val constructedLoaders = CopyOnWriteArrayList<ClassLoader>()

        fun record(event: String) { events += event }
        fun count(event: String): Int = events.count { it == event }
        fun reset() { events.clear() }
        fun resetAll() { events.clear(); constructedLoaders.clear() }
    }

    /**
     * Gates fixture plugin callbacks block on, so a test can hold one transition mid-flight and
     * prove what a racing second transition does — or, decisively, does not do.
     *
     * Plain latches, not deferreds: a fixture callback must not actually suspend. A suspending
     * callback compiles a nested state-machine class into the host classpath, which the plugin
     * loader then resolves through parent delegation and cannot link against the snapshot-defined
     * provider ([IllegalAccessError]). With no suspension point the provider is fully
     * self-contained in its JAR. Latches default to open, so an ungated callback runs straight
     * through.
     */
    object Gate {
        /** A held callback: [entered] fires when the callback starts, [release] unblocks it. */
        class Hold(val entered: CountDownLatch, val release: CountDownLatch)

        private fun open() = CountDownLatch(0)

        @Volatile private var enableEntered = open()
        @Volatile private var enableRelease = open()
        @Volatile private var disableEntered = open()
        @Volatile private var disableRelease = open()

        @Volatile var lastEnableHold: Hold? = null
        @Volatile var lastDisableHold: Hold? = null

        /** Holds the *next* enable only; later callbacks run on open latches. */
        fun holdEnable(): Hold = Hold(CountDownLatch(1), CountDownLatch(1)).also {
            enableEntered = it.entered
            enableRelease = it.release
            lastEnableHold = it
        }

        fun holdDisable(): Hold = Hold(CountDownLatch(1), CountDownLatch(1)).also {
            disableEntered = it.entered
            disableRelease = it.release
            lastDisableHold = it
        }

        /** Consumes the current hold and re-opens the gate for any later callback. */
        fun takeEnable(): Hold = Hold(enableEntered, enableRelease).also {
            enableEntered = open()
            enableRelease = open()
        }

        fun takeDisable(): Hold = Hold(disableEntered, disableRelease).also {
            disableEntered = open()
            disableRelease = open()
        }

        fun reset() {
            enableEntered = open(); enableRelease = open()
            disableEntered = open(); disableRelease = open()
            lastEnableHold = null; lastDisableHold = null
        }
    }

    /** Whether the descriptor the plugin JAR actually contains is still reachable. */
    fun ClassLoader.providerDescriptorStillReachable(): Boolean =
        getResource(PROVIDER_DESCRIPTOR) != null
}

/**
 * Whether this loader can still serve the plugin descriptor — false once the loader is closed.
 * Top-level so every test file can call it as an extension.
 */
internal fun ClassLoader.providerDescriptorStillReachable(): Boolean =
    getResource(PluginTestFixtures.PROVIDER_DESCRIPTOR) != null

/** A provider whose every lifecycle callback is recorded and can be gated. */
internal class RecordingPlugin : Plugin<PluginSettings.None> {
    init {
        PluginTestFixtures.Log.record("construct")
        PluginTestFixtures.Log.constructedLoaders += javaClass.classLoader
    }

    override suspend fun initialize(context: PluginContext): Result<Unit, ServiceError> {
        PluginTestFixtures.Log.record("initialize")
        return Ok(Unit)
    }

    override suspend fun onEnable(): Result<Unit, ServiceError> {
        PluginTestFixtures.Log.record("enable-enter")
        val hold = PluginTestFixtures.Gate.takeEnable()
        hold.entered.countDown()
        hold.release.await()
        PluginTestFixtures.Log.record("enable-exit")
        return Ok(Unit)
    }

    override suspend fun onDisable() {
        PluginTestFixtures.Log.record("disable-enter")
        val hold = PluginTestFixtures.Gate.takeDisable()
        hold.entered.countDown()
        hold.release.await()
        PluginTestFixtures.Log.record("disable-exit")
    }

    override suspend fun shutdown() {
        PluginTestFixtures.Log.record("plugin-shutdown")
    }

    override fun getServices(): List<Service> {
        PluginTestFixtures.Log.record("getServices")
        return emptyList()
    }

    override fun getSettings(): PluginSettings.None = PluginSettings.None
}

/** A provider whose constructor always throws, for fail-closed paths. */
internal class ExplodingPlugin : Plugin<PluginSettings.None> {
    init {
        PluginTestFixtures.Log.record("construct")
        error("fixture: this provider's constructor always fails")
    }

    override suspend fun initialize(context: PluginContext): Result<Unit, ServiceError> = Ok(Unit)
    override fun getServices(): List<Service> = emptyList()
    override fun getSettings(): PluginSettings.None = PluginSettings.None
}

/**
 * Writes a real plugin JAR whose provider is [providerClass].
 *
 * [description] varies the bytes without varying the declared id, which is what a modified
 * external plugin looks like.
 */
internal fun writePluginJar(
    directory: File,
    name: String,
    id: String,
    providerClass: Class<*>,
    description: String = "fixture plugin"
): File {
    directory.mkdirs()
    val file = directory.resolve(name)
    JarOutputStream(file.outputStream()).use { out ->
        out.putNextEntry(JarEntry(PluginTestFixtures.PROVIDER_DESCRIPTOR))
        out.write(providerClass.name.toByteArray())
        out.closeEntry()
        out.putNextEntry(JarEntry("plugin.json"))
        out.write(
            """{"id":"$id","name":"$id","version":"1.0.0","author":"fixture","description":"$description","minApiVersion":"${ApiVersion.VERSION}"}""".toByteArray()
        )
        out.closeEntry()
        val classPath = providerClass.name.replace('.', '/') + ".class"
        out.putNextEntry(JarEntry(classPath))
        requireNotNull(providerClass.classLoader.getResourceAsStream(classPath)).use { it.copyTo(out) }
        out.closeEntry()
    }
    return file
}

internal object FixtureLoggerFactory : LoggerFactory {
    override fun getLogger(name: String): Logger = FixtureLogger
}

internal object FixtureLogger : Logger {
    override fun debug(message: String) = Unit
    override fun info(message: String) = Unit
    override fun warn(message: String) = Unit
    override fun error(message: String, error: Throwable?) = Unit
}
