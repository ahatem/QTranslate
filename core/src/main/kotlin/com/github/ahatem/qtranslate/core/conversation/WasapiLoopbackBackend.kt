package com.github.ahatem.qtranslate.core.conversation

import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * Records what a Windows output device is playing, through WASAPI loopback.
 *
 * Java Sound cannot open an output device for recording, so this runs a bundled PowerShell script
 * that compiles a small WASAPI client and streams PCM over stdout — the same approach the system
 * services plugin takes for WinRT OCR and speech, and it needs nothing installed. Each "device"
 * here is an output: recording "Headphones" captures everything played to them, while a
 * dedicated chat output (SteelSeries Sonar's "Chat", or Discord pointed at its own device)
 * captures only the call.
 *
 * Only available on Windows; [isSupported] says whether it can be used.
 */
class WasapiLoopbackBackend(
    private val powershell: String? = defaultPowerShell()
) : AudioBackend {

    val isSupported: Boolean get() = powershell != null

    private val script: File by lazy {
        val target = File(System.getProperty("java.io.tmpdir"), "qtranslate-wasapi-loopback-$SCRIPT_VERSION.ps1")
        val bytes = javaClass.getResourceAsStream(SCRIPT_RESOURCE)?.use(InputStream::readBytes)
            ?: error("Missing resource $SCRIPT_RESOURCE")
        if (!target.isFile || !target.readBytes().contentEquals(bytes)) target.writeBytes(bytes)
        target
    }

    override fun inputDevices(): List<AudioDevice> {
        val executable = powershell ?: return emptyList()
        return runCatching {
            val process = ProcessBuilder(command(executable, "list")).redirectErrorStream(false).start()
            process.outputStream.close()
            val lines = process.inputStream.bufferedReader(Charsets.UTF_8).readLines()
            if (!process.waitFor(LIST_TIMEOUT_SECONDS, TimeUnit.SECONDS)) process.destroyForcibly()
            lines.mapNotNull { line ->
                val id = line.substringBefore('\t').trim()
                val name = line.substringAfter('\t', "").trim()
                if (id.isEmpty()) null else AudioDevice(id = id, name = name.ifEmpty { id })
            }
        }.getOrDefault(emptyList())
    }

    /** Lists running applications currently registered with active audio sessions. */
    fun activeApplications(): List<AudioApplication> {
        val executable = powershell ?: return emptyList()
        return runCatching {
            val process = ProcessBuilder(command(executable, "list-apps")).redirectErrorStream(false).start()
            process.outputStream.close()
            val lines = process.inputStream.bufferedReader(Charsets.UTF_8).readLines()
            if (!process.waitFor(LIST_TIMEOUT_SECONDS, TimeUnit.SECONDS)) process.destroyForcibly()
            lines.mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size < 5) return@mapNotNull null
                val pid = parts[0].trim().toIntOrNull() ?: return@mapNotNull null
                val procName = parts[1].trim()
                val windowTitle = parts[2].trim()
                val devId = parts[3].trim()
                val devName = parts[4].trim()
                if (devId.isEmpty() || procName.isEmpty()) null
                else AudioApplication(
                    pid = pid,
                    processName = procName,
                    windowTitle = windowTitle,
                    device = AudioDevice(id = devId, name = devName.ifEmpty { devId })
                )
            }
        }.getOrDefault(emptyList())
    }

    override fun open(device: AudioDevice): PcmStream {
        val executable = powershell ?: throw AudioDeviceUnavailableException(device)
        val process = try {
            ProcessBuilder(command(executable, "capture") + listOf("-DeviceId", device.id)).start()
        } catch (e: Exception) {
            throw AudioDeviceUnavailableException(device, e)
        }
        val input = DataInputStream(process.inputStream)
        val header = ByteArray(8)
        try {
            input.readFully(header)
        } catch (e: Exception) {
            val reason = process.errorStream.bufferedReader().readText().trim()
            process.destroyForcibly()
            throw AudioDeviceUnavailableException(device, if (reason.isEmpty()) e else Exception(reason, e))
        }
        val format = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val rate = format.int
        val channels = format.int
        return LoopbackPcmStream(process, input, MonoResampler(rate, channels), channels)
    }

    private fun command(executable: String, action: String): List<String> = listOf(
        executable, "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
        "-ExecutionPolicy", "Bypass", "-File", script.absolutePath, "-Command", action
    )

    private class LoopbackPcmStream(
        private val process: Process,
        private val input: InputStream,
        private val resampler: MonoResampler,
        channels: Int
    ) : PcmStream {
        // A pipe read can end mid-frame; the partial frame is carried into the next read.
        private val chunk = ByteArray(4096 * channels * 2)
        private val frameBytes = channels * 2
        private var carry = ByteArray(0)

        @Volatile
        private var closed = false

        override fun read(): ByteArray? {
            if (closed) return null
            val count = try {
                input.read(chunk)
            } catch (e: Exception) {
                if (closed) return null else throw e
            }
            if (count < 0) {
                if (closed) return null
                throw IllegalStateException(process.errorStream.bufferedReader().readText().trim()
                    .ifEmpty { "System audio capture stopped unexpectedly." })
            }
            val data = if (carry.isEmpty()) chunk.copyOf(count) else carry + chunk.copyOf(count)
            val whole = data.size / frameBytes * frameBytes
            carry = data.copyOfRange(whole, data.size)
            return resampler.process(data, whole)
        }

        override fun close() {
            if (closed) return
            closed = true
            // Closing stdin is the script's stop signal; give it a moment to release the device.
            runCatching { process.outputStream.close() }
            if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }

    private companion object {
        const val SCRIPT_RESOURCE = "/conversation/wasapi-loopback.ps1"
        /** Part of the extracted file's name, so an older copy left in temp is never reused. */
        const val SCRIPT_VERSION = 2
        const val LIST_TIMEOUT_SECONDS = 20L

        fun defaultPowerShell(): String? {
            if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return null
            val systemRoot = System.getenv("SystemRoot") ?: return null
            return File(systemRoot, "System32/WindowsPowerShell/v1.0/powershell.exe")
                .takeIf { it.isFile }?.absolutePath
        }
    }
}
