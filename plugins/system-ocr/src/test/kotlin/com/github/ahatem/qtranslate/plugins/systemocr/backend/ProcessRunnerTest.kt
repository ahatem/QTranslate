package com.github.ahatem.qtranslate.plugins.systemocr.backend

import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.plugins.systemocr.unwrap
import com.github.ahatem.qtranslate.plugins.systemocr.unwrapError
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Exercises [RealProcessRunner] against real child processes: this project's [SleeperMain] launched
 * in a JVM, so no shell utility is involved and it behaves the same on every platform.
 */
class ProcessRunnerTest {

    private val runner = RealProcessRunner()

    private val javaExecutable: String = File(
        System.getProperty("java.home"),
        "bin/" + if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java",
    ).absolutePath

    private fun sleeper(vararg args: String): List<String> = listOf(
        javaExecutable,
        "-cp",
        System.getProperty("java.class.path"),
        SleeperMain::class.java.name,
    ) + args

    @Test
    fun `captures stdout and the exit code`() = runBlocking {
        val outcome = runner.run(sleeper("echo"), 30_000).unwrap()

        assertFalse(outcome.timedOut)
        assertEquals(0, outcome.exitCode)
        assertTrue(outcome.stdout.contains("hello-from-helper"))
    }

    @Test
    fun `captures stderr`() = runBlocking {
        val outcome = runner.run(listOf(javaExecutable, "-version"), 30_000).unwrap()

        assertEquals(0, outcome.exitCode)
        assertTrue(outcome.stderr.contains("version", ignoreCase = true))
    }

    @Test
    fun `a program that cannot be launched is an Err rather than a thrown exception`() = runBlocking {
        val absent = File(Files.createTempDirectory("process-runner-absent").toFile(), "no-such-program").absolutePath

        val error = runner.run(listOf(absent), 5_000).unwrapError()

        assertTrue(error is ServiceError.ConfigurationError, "was ${error::class.simpleName}")
    }

    @Test
    fun `reports a timeout instead of blocking forever`() = runBlocking {
        val outcome = runner.run(sleeper("sleep", tempPidPath(), "30000"), 500).unwrap()

        assertTrue(outcome.timedOut)
        assertEquals(-1, outcome.exitCode)
    }

    @Test
    fun `cancelling the caller kills the child process`() = runBlocking {
        val pidFile = File(tempPidPath())

        val job = launch { runCatching { runner.run(sleeper("sleep", pidFile.absolutePath, "30000"), 60_000) } }
        val pid = awaitPid(pidFile)
        assertTrue(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))

        job.cancelAndJoin()

        assertTrue(awaitStopped(pid), "the child should have been killed when the caller was cancelled")
    }

    private fun tempPidPath(): String =
        Files.createTempFile("sleeper", ".pid").toFile().apply { delete() }.absolutePath

    private suspend fun awaitPid(file: File, timeoutMillis: Long = 30_000): Long =
        withTimeout(timeoutMillis) {
            while (true) {
                if (file.isFile && file.length() > 0L) return@withTimeout file.readText().trim().toLong()
                delay(50)
            }
            error("unreachable")
        }

    private suspend fun awaitStopped(pid: Long, timeoutMillis: Long = 30_000): Boolean =
        withTimeoutOrNull(timeoutMillis) {
            while (ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) {
                delay(50)
            }
            true
        } ?: false
}
