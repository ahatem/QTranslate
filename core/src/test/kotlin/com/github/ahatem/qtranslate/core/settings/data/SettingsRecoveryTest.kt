package com.github.ahatem.qtranslate.core.settings.data

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.shared.AppConstants
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What happens when a stored configuration cannot be read.
 *
 * This used to fall back to defaults and write a line to a log nobody opens. From the user's side
 * the application looked freshly installed — every preset, service choice, hotkey and window
 * position apparently forgotten — and the natural response, setting it all up again, overwrote
 * the very record that still held the original.
 *
 * These tests exist because the recovery path only runs when something has already gone wrong,
 * which is the worst time to discover it does not work.
 */
class SettingsRecoveryTest {

    private val logger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val tempDir: File = Files.createTempDirectory("qtranslate-recovery").toFile()

    @AfterTest
    fun cleanUp() {
        tempDir.deleteRecursively()
    }

    /**
     * A directory of its own per repository.
     *
     * DataStore refuses more than one instance over the same file in a process, so a test that
     * writes with one repository and reads with another has to point the second at a copy.
     */
    private fun dir(name: String) = File(tempDir, name).apply { File(this, "datastore").mkdirs() }

    private fun repository(directory: File) = SettingsRepository(directory, json, logger)

    /**
     * A directory holding a real datastore file, written by a repository of its own.
     *
     * The file is protobuf, so it is produced rather than hand-written: crafting one by hand
     * would be testing an encoding rather than this class's behaviour.
     */
    private suspend fun seedStore(name: String): File {
        val seed = dir(name)
        val repo = repository(seed)
        val written = repo.updateConfiguration(Configuration.DEFAULT)
        repo.configuration.first()   // forces the write to land before the file is read

        val file = File(seed, "datastore/${AppConstants.DATASTORE_FILE}")
        assertTrue(file.exists(), "expected a datastore file; write returned $written")
        return seed
    }

    /**
     * Rewrites the stored JSON in place, keeping the byte count identical.
     *
     * The length matters. The JSON sits inside a protobuf field with a length prefix, so a
     * replacement of a different size corrupts the envelope and the store stops parsing at all —
     * which is a different failure with a different recovery, covered separately below.
     */
    private fun corruptJsonInPlace(directory: File) {
        val file = File(directory, "datastore/${AppConstants.DATASTORE_FILE}")
        val raw = file.readBytes().toString(Charsets.ISO_8859_1)
        val match = Regex("""\{"configVersion".*?\}""", RegexOption.DOT_MATCHES_ALL).find(raw)
        assertNotNull(match, "expected to find the stored configuration JSON in the datastore file")

        // Same length, still not valid as a Configuration: a list field given a string.
        val broken = """{"servicePresets":"broken""" + " ".repeat(match.value.length - 27) + """"}"""
        assertEquals(match.value.length, broken.length, "replacement must be the same length")

        file.writeBytes(raw.replace(match.value, broken).toByteArray(Charsets.ISO_8859_1))
    }

    @Test
    fun `a readable configuration reports no recovery`() = runBlocking {
        val repo = repository(dir("healthy"))
        repo.updateConfiguration(Configuration.DEFAULT.copy(uiScale = 125))

        val loaded = repo.configuration.first()

        assertEquals(125, loaded.uiScale)
        assertNull(repo.lastRecovery, "a healthy load must not report a recovery")
    }

    /**
     * A fresh install has no stored configuration, and that is not a failure.
     *
     * Reporting one here would show an alarming message to every first-time user, which is the
     * quickest way to teach people to dismiss the message without reading it.
     */
    @Test
    fun `a fresh install is not treated as a failure`() = runBlocking {
        val repo = repository(dir("fresh"))

        val loaded = repo.configuration.first()

        assertEquals(Configuration.DEFAULT.uiScale, loaded.uiScale)
        assertNull(repo.lastRecovery, "an empty datastore is a new install, not a broken one")
    }

    /** Configuration this version cannot parse, in a file that is otherwise intact. */
    @Test
    fun `unparseable configuration is backed up rather than discarded`() = runBlocking {
        val directory = seedStore("bad-json")
        corruptJsonInPlace(directory)

        val repo = repository(directory)
        val loaded = repo.configuration.first()

        assertEquals(Configuration.DEFAULT.uiScale, loaded.uiScale, "should fall back to defaults")

        val recovery = repo.lastRecovery
        assertNotNull(recovery, "a failed load must be reported, not swallowed")

        val backup = recovery.backupFile
        assertNotNull(backup, "the unreadable configuration must be kept")
        assertTrue(backup.exists(), "the backup should be on disk at ${backup.absolutePath}")
        assertTrue(
            backup.readText().contains("broken"),
            "the backup must hold the original contents, not the defaults written over them"
        )
        assertTrue(
            recovery.reason.contains(backup.name),
            "the message should say where the settings went: was '${recovery.reason}'"
        )
    }

    /**
     * A datastore file too damaged to open at all.
     *
     * There is no configuration string to keep here, so the raw file is copied instead. A corrupt
     * protobuf is often still legible enough to recover an endpoint or a service choice by hand,
     * and it is the user's data either way.
     */
    @Test
    fun `an unreadable store file is copied aside`() = runBlocking {
        val directory = seedStore("bad-file")
        File(directory, "datastore/${AppConstants.DATASTORE_FILE}")
            .writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04))

        val repo = repository(directory)
        val loaded = repo.configuration.first()

        assertEquals(Configuration.DEFAULT.uiScale, loaded.uiScale, "should fall back to defaults")

        val recovery = repo.lastRecovery
        assertNotNull(recovery, "an unreadable store must be reported, not swallowed")

        val backup = recovery.backupFile
        assertNotNull(backup, "the damaged file must be kept")
        assertTrue(backup.exists(), "the copy should be on disk at ${backup.absolutePath}")
        assertEquals(5, backup.readBytes().size, "the copy should be the damaged file, byte for byte")
    }
}
