package com.github.ahatem.qtranslate.plugins.systemocr.backend

import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/** The bounded outcome of running a helper process. */
internal data class ProcessOutcome(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
)

/**
 * Runs a helper process, and is the seam the backends are tested against.
 *
 * Commands are passed as an argument list, never a shell string, so no argument can be
 * reinterpreted as a command. Failing to start the program is an `Err` rather than a throw, so
 * callers stay inside the `Result<…, ServiceError>` contract.
 */
internal interface ProcessRunner {
    suspend fun run(command: List<String>, timeoutMillis: Long): Result<ProcessOutcome, ServiceError>
}

/** The production [ProcessRunner], backed by [ProcessBuilder]. */
internal class RealProcessRunner : ProcessRunner {

    override suspend fun run(
        command: List<String>,
        timeoutMillis: Long,
    ): Result<ProcessOutcome, ServiceError> = withContext(Dispatchers.IO) {
        val process = try {
            ProcessBuilder(command).start()
        } catch (exception: IOException) {
            // Missing program, not executable, or blocked by the OS. CancellationException is a
            // different type and still propagates.
            return@withContext Err(launchFailure(command, exception))
        } catch (exception: SecurityException) {
            return@withContext Err(launchFailure(command, exception))
        }

        // Drain both pipes concurrently, or a full buffer would block the process.
        val stdout = Drain(process.inputStream)
        val stderr = Drain(process.errorStream)

        val finished = try {
            runInterruptible { process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS) }
        } catch (cancellation: CancellationException) {
            // Kill the child before letting cancellation through.
            process.destroyForcibly()
            throw cancellation
        }

        if (!finished) {
            process.destroyForcibly()
            process.waitFor()
        }

        stdout.thread.join(JOIN_TIMEOUT_MS)
        stderr.thread.join(JOIN_TIMEOUT_MS)

        Ok(
            ProcessOutcome(
                exitCode = if (finished) process.exitValue() else TIMED_OUT_EXIT_CODE,
                stdout = stdout.text(),
                stderr = stderr.text(),
                timedOut = !finished,
            )
        )
    }

    /** A launch failure is an environment problem, so it maps to [ServiceError.ConfigurationError]. */
    private fun launchFailure(command: List<String>, cause: Throwable): ServiceError {
        val program = command.firstOrNull().orEmpty()
        val detail = cause.message?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()
        return ServiceError.ConfigurationError(
            "Could not start '$program'" + (detail?.let { ": $it" } ?: "") +
                ". The program may be missing, not executable, or blocked by the system.",
            cause,
        )
    }

    private class Drain(stream: InputStream) {
        private val buffer = ByteArrayOutputStream()

        val thread: Thread = Thread {
            stream.use { it.copyTo(buffer) }
        }.apply {
            isDaemon = true
            start()
        }

        fun text(): String = buffer.toByteArray().toString(Charsets.UTF_8)
    }

    private companion object {
        const val TIMED_OUT_EXIT_CODE = -1
        const val JOIN_TIMEOUT_MS = 2_000L
    }
}
