package com.github.ahatem.qtranslate.plugins.systemservices.tts

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.plugins.systemservices.FakeProcessRunner
import com.github.ahatem.qtranslate.plugins.systemservices.argumentValue
import com.github.ahatem.qtranslate.plugins.systemservices.backend.ExecutableLocator
import com.github.ahatem.qtranslate.plugins.systemservices.backend.ProcessOutcome
import com.github.ahatem.qtranslate.plugins.systemservices.backend.ProcessRunner
import com.github.michaelbull.result.Result
import com.github.ahatem.qtranslate.plugins.systemservices.unwrap
import com.github.ahatem.qtranslate.plugins.systemservices.unwrapError
import com.github.ahatem.qtranslate.plugins.systemservices.wav
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SystemTtsBackendsTest {
    private fun ok() = ProcessOutcome(0, "", "", false)

    @Test fun `Linux prefers espeak-ng and handles missing engines`() {
        val directory = Files.createTempDirectory("tts-factory").toFile()
        val locator = ExecutableLocator { name -> if (name == "espeak-ng") "/usr/bin/espeak-ng" else null }
        val runner = FakeProcessRunner { ok() }
        assertIs<LinuxTtsBackend>(SystemTtsBackends.create("Linux", directory, runner, locator).unwrap())
        assertIs<ServiceError.ConfigurationError>(
            SystemTtsBackends.create("Linux", directory, runner, ExecutableLocator { null }).unwrapError())
    }

    @Test fun `Linux voice discovery and synthesis keep text out of arguments and clean files`() = runBlocking {
        var input: File? = null
        var output: File? = null
        val runner = FakeProcessRunner { command ->
            if ("--voices" in command) {
                ProcessOutcome(0, "Pty Language Age/Gender VoiceName File Other Languages\n 5 en-us M english-us en\n 5 ar F arabic ar\n", "", false)
            } else {
                input = File(command.argumentValue("-f")!!)
                output = File(command.argumentValue("-w")!!)
                assertEquals("مرحبا private", input!!.readText(Charsets.UTF_8))
                output!!.writeBytes(wav())
                ok()
            }
        }
        val backend = LinuxTtsBackend(runner, "/usr/bin/espeak-ng")
        assertEquals(listOf("english-us", "arabic"), backend.discoverVoices().unwrap().map { it.id })
        assertEquals(wav().size, backend.synthesize("مرحبا private", "arabic", 1f).unwrap().size)
        assertTrue(runner.commands.last().none { it.contains("مرحبا") || it.contains("private") })
        assertEquals("1", runner.commands.last().argumentValue("-b"))
        assertFalse(input!!.exists())
        assertFalse(output!!.exists())
    }

    @Test fun `Windows voice discovery and synthesis use a file and clean it`() = runBlocking {
        var input: File? = null
        var output: File? = null
        val runner = FakeProcessRunner { command ->
            output = File(command.argumentValue("-OutputPath")!!)
            if ("voices" in command) {
                output!!.writeText("""[{"id":"HKEY_LOCAL_MACHINE\\WinRT\\Voice\\David","name":"David","locale":"en-US","gender":"Male"}]""")
            } else {
                input = File(command.argumentValue("-InputPath")!!)
                assertEquals("Unicode مرحبا", input!!.readText(Charsets.UTF_8))
                assertEquals("HKEY_LOCAL_MACHINE\\WinRT\\Voice\\David", command.argumentValue("-Voice"))
                assertEquals("1.0", command.argumentValue("-Rate"))
                output!!.writeBytes(wav())
            }
            ok()
        }
        val backend = WindowsTtsBackend(runner, "powershell.exe", File("helper.ps1"))
        assertEquals("HKEY_LOCAL_MACHINE\\WinRT\\Voice\\David", backend.discoverVoices().unwrap().single().id)
        assertEquals(wav().size, backend.synthesize("Unicode مرحبا", "HKEY_LOCAL_MACHINE\\WinRT\\Voice\\David", 1f).unwrap().size)
        assertTrue(runner.commands.last().none { "Unicode" in it || "مرحبا" in it })
        assertFalse(input!!.exists())
        assertFalse(output!!.exists())
    }

    @Test fun `macOS discovers voices and converts file to WAV`() = runBlocking {
        var input: File? = null
        var output: File? = null
        val runner = FakeProcessRunner { command ->
            when {
                "?" in command -> ProcessOutcome(0, "Alex                en_US    # Hello\nAmira               ar_EG    # مرحبا\n", "", false)
                command.first() == "/usr/bin/say" -> {
                    input = File(command.argumentValue("-f")!!)
                    File(command.argumentValue("-o")!!).writeText("AIFF")
                    ok()
                }
                else -> {
                    output = File(command.last())
                    output!!.writeBytes(wav())
                    ok()
                }
            }
        }
        val backend = MacTtsBackend(runner, "/usr/bin/say", "/usr/bin/afconvert")
        assertEquals(listOf("Alex", "Amira"), backend.discoverVoices().unwrap().map { it.id })
        backend.synthesize("private text", "Alex", 1f).unwrap()
        assertTrue(runner.commands.none { command -> command.any { "private text" in it } })
        assertFalse(input!!.exists())
        assertFalse(output!!.exists())
    }

    @Test fun `macOS locale parsing accepts numeric regions and scripts`() {
        val backend = MacTtsBackend(FakeProcessRunner { ok() }, "say", "afconvert")
        val cases = mapOf("en_US" to "en-US", "ar_SA" to "ar-SA",
            "es_419" to "es-419", "ar_001" to "ar-001",
            "zh_Hant_TW" to "zh-Hant-TW")
        cases.forEach { (input, expected) ->
            assertEquals(expected, backend.parseVoiceLine("Voice Name    $input    # sample")?.locale)
        }
        assertEquals(null, backend.parseVoiceLine("Voice Name    invalid!    # sample"))
    }

    @Test fun `Windows helper errors expose native code without leaking text`() = runBlocking {
        val backend = WindowsTtsBackend(FakeProcessRunner {
            ProcessOutcome(1, "", "winrt_speech_failed HRESULT=0x800455A0 type=System.Exception", false)
        }, "powershell.exe", File("helper.ps1"))
        val error = backend.discoverVoices().unwrapError()
        assertIs<ServiceError.ServiceUnavailableError>(error)
        assertTrue(error.message.contains("0x800455A0"))
        val missing = WindowsTtsBackend(FakeProcessRunner {
            ProcessOutcome(2, "", "voice_not_installed", false)
        }, "powershell.exe", File("helper.ps1"))
        assertIs<ServiceError.InvalidInputError>(missing.synthesize("private text", "gone", 1f).unwrapError())
    }

    @Test fun `Windows helper source uses WinRT and file argument plan`() {
        val script = checkNotNull(javaClass.getResourceAsStream("/scripts/windows-tts.ps1"))
            .bufferedReader().use { it.readText() }
        assertTrue("SpeechSynthesizer]::AllVoices" in script)
        assertTrue("SynthesizeTextToStreamAsync" in script)
        assertTrue("System.WindowsRuntimeSystemExtensions" in script)
        assertTrue("ReadAllText($" + "InputPath" in script)
        assertFalse("SAPI.SpVoice" in script)
        val command = WindowsTtsBackend(FakeProcessRunner { ok() }, "powershell.exe", File("helper.ps1"))
            .command("synthesize", File("output.wav"))
        assertEquals("synthesize", command.argumentValue("-Command"))
        assertEquals(File("output.wav").absolutePath, command.argumentValue("-OutputPath"))
        assertFalse(command.any { "private text" in it })
    }
    @Test fun `cancellation deletes TTS input and output files`() = runBlocking {
        val paths = CompletableDeferred<Pair<File, File>>()
        val runner = object : ProcessRunner {
            override suspend fun run(command: List<String>, timeoutMillis: Long): Result<ProcessOutcome, ServiceError> {
                paths.complete(File(command.argumentValue("-f")!!) to File(command.argumentValue("-w")!!))
                awaitCancellation()
            }
        }
        val backend = LinuxTtsBackend(runner, "espeak")
        val job = launch { backend.synthesize("private cancellation text", "english", 1f) }
        val (input, output) = paths.await()
        assertTrue(input.exists())
        job.cancelAndJoin()
        assertFalse(input.exists())
        assertFalse(output.exists())
    }

    @Test fun `timeouts nonzero exits and empty output fail`() = runBlocking {
        val backend = LinuxTtsBackend(FakeProcessRunner {
            if ("--voices" in it) ProcessOutcome(-1, "", "", true) else ok()
        }, "espeak")
        assertIs<ServiceError.TimeoutError>(backend.discoverVoices().unwrapError())
        val failed = LinuxTtsBackend(FakeProcessRunner { ProcessOutcome(4, "", "private", false) }, "espeak")
        val error = failed.synthesize("secret", "english", 1f).unwrapError()
        assertIs<ServiceError.ServiceUnavailableError>(error)
        assertFalse(error.message.contains("secret"))
        val empty = LinuxTtsBackend(FakeProcessRunner { ok() }, "espeak")
        assertIs<ServiceError.InvalidResponseError>(empty.synthesize("secret", "english", 1f).unwrapError())
    }
}
