package com.github.ahatem.qtranslate.plugins.systemservices.spell

import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.Executors

/** Thin, thread-confined binding to the Windows 8+ Spell Checking COM API. */
internal class WindowsSpellCheckerBackend(
    private val native: WindowsSpellNative = JnaWindowsSpellNative(),
) : SystemSpellCheckerBackend {
    override val displayName = "Windows Spell Checking"
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "system-spell-com").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    override suspend fun languages(): Result<Set<String>, ServiceError> = invoke { native.languages() }

    override suspend fun check(text: String, languageTag: String): Result<List<SpellingFinding>, ServiceError> =
        invoke { native.check(text, languageTag) }

    override suspend fun close() {
        try { withContext(NonCancellable + worker) { native.close() } }
        finally { worker.close() }
    }

    private suspend fun <T> invoke(block: () -> T): Result<T, ServiceError> = try {
        Ok(withContext(worker) { block() })
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: WindowsSpellFailure) {
        Err(ServiceError.ServiceUnavailableError("Windows spell checking failed (${failure.operation}, HRESULT ${failure.code})."))
    } catch (_: LinkageError) {
        Err(ServiceError.ServiceUnavailableError("Windows spell checking is unavailable."))
    } catch (_: Exception) {
        Err(ServiceError.ServiceUnavailableError("Windows spell checking is unavailable."))
    }
}

internal interface WindowsSpellNative {
    fun languages(): Set<String>
    fun check(text: String, languageTag: String): List<SpellingFinding>
    fun close() {}
}

internal class WindowsSpellFailure(val operation: String, val code: String) : RuntimeException()

internal class JnaWindowsSpellNative : WindowsSpellNative {
    private interface Ole32 : StdCallLibrary {
        fun CoInitializeEx(reserved: Pointer?, flags: Int): Int
        fun CoUninitialize()
        fun CoCreateInstance(clsid: Pointer, outer: Pointer?, context: Int, iid: Pointer, result: PointerByReference): Int
        fun CoTaskMemFree(value: Pointer)
    }

    private val ole: Ole32 by lazy { Native.load("Ole32", Ole32::class.java) }
    private var factory: Pointer? = null
    private var ownsApartment = false
    private val checkers = mutableMapOf<String, Pointer>()

    override fun languages(): Set<String> {
        val factory = factory()
        val output = PointerByReference()
        call(factory, 3, "languages", output)
        val enumerator = output.value ?: fail("languages", -1)
        return try { strings(enumerator).toSet() } finally { release(enumerator) }
    }

    override fun check(text: String, languageTag: String): List<SpellingFinding> {
        val checker = checkers.getOrPut(languageTag) {
            val supported = IntByReference()
            call(factory(), 4, "language support", WString(languageTag), supported)
            if (supported.value == 0) throw WindowsSpellFailure("language support", "unsupported")
            val output = PointerByReference()
            call(factory(), 5, "create checker", WString(languageTag), output)
            output.value ?: fail("create checker", -1)
        }
        val errorsOutput = PointerByReference()
        call(checker, 4, "check", WString(text), errorsOutput)
        val errors = errorsOutput.value ?: fail("check", -1)
        return try { errors(checker, errors, text) } finally { release(errors) }
    }

    override fun close() {
        checkers.values.forEach(::release)
        checkers.clear()
        factory?.let(::release)
        factory = null
        if (ownsApartment) ole.CoUninitialize()
        ownsApartment = false
    }

    private fun errors(checker: Pointer, enumerator: Pointer, text: String): List<SpellingFinding> {
        val findings = mutableListOf<SpellingFinding>()
        while (true) {
            val next = PointerByReference()
            val status = call(enumerator, 3, "next error", next)
            if (status == 1) break
            val error = next.value ?: fail("next error", -1)
            try {
                val start = IntByReference()
                val length = IntByReference()
                val action = IntByReference()
                call(error, 3, "error start", start)
                call(error, 4, "error length", length)
                call(error, 5, "error action", action)
                val end = start.value.toLong() + length.value
                if (start.value < 0 || length.value <= 0 || end > text.length) fail("error range", -1)
                val original = text.substring(start.value, end.toInt())
                val suggestions = when (action.value) {
                    1 -> {
                        val output = PointerByReference()
                        call(checker, 5, "suggest", WString(original), output)
                        val suggestionEnum = output.value ?: fail("suggest", -1)
                        try { strings(suggestionEnum) } finally { release(suggestionEnum) }
                    }
                    2 -> {
                        val output = PointerByReference()
                        call(error, 6, "replacement", output)
                        val replacement = output.value
                        if (replacement == null) emptyList() else try { listOf(replacement.getWideString(0)) }
                        finally { ole.CoTaskMemFree(replacement) }
                    }
                    else -> emptyList()
                }
                if (action.value != 0) findings += SpellingFinding(start.value, length.value, suggestions, original)
            } finally { release(error) }
        }
        return findings
    }

    private fun strings(enumerator: Pointer): List<String> {
        val items = mutableListOf<String>()
        while (true) {
            val output = PointerByReference()
            val fetched = IntByReference()
            val status = call(enumerator, 3, "next string", 1, output, fetched)
            val value = output.value
            if (value != null) try { items += value.getWideString(0) } finally { ole.CoTaskMemFree(value) }
            if (status == 1 || fetched.value == 0) break
        }
        return items
    }

    private fun factory(): Pointer {
        factory?.let { return it }
        val init = ole.CoInitializeEx(null, 0)
        ownsApartment = init == 0 || init == 1
        if (!ownsApartment && init != RPC_E_CHANGED_MODE) checkHresult(init, "COM initialization")
        try {
            val output = PointerByReference()
            checkHresult(ole.CoCreateInstance(guid(FACTORY_CLSID), null, 1, guid(FACTORY_IID), output), "create factory")
            return (output.value ?: fail("create factory", -1)).also { factory = it }
        } catch (failure: Throwable) {
            if (ownsApartment) ole.CoUninitialize()
            ownsApartment = false
            throw failure
        }
    }

    private fun call(instance: Pointer, index: Int, operation: String, vararg args: Any): Int {
        val vtable = instance.getPointer(0)
        val method = vtable.getPointer(index.toLong() * Native.POINTER_SIZE)
        val status = Function.getFunction(method, Function.ALT_CONVENTION).invokeInt(arrayOf(instance, *args))
        checkHresult(status, operation)
        return status
    }

    private fun release(instance: Pointer) {
        val method = instance.getPointer(0).getPointer(2L * Native.POINTER_SIZE)
        Function.getFunction(method, Function.ALT_CONVENTION).invokeInt(arrayOf(instance))
    }

    private fun checkHresult(status: Int, operation: String) {
        if (status < 0) throw WindowsSpellFailure(operation, "0x${status.toUInt().toString(16)}")
    }

    private fun fail(operation: String, status: Int): Nothing =
        throw WindowsSpellFailure(operation, "0x${status.toUInt().toString(16)}")

    private fun guid(value: String): Memory {
        val uuid = UUID.fromString(value)
        val bytes = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        bytes.putInt((uuid.mostSignificantBits ushr 32).toInt())
        bytes.putShort((uuid.mostSignificantBits ushr 16).toShort())
        bytes.putShort(uuid.mostSignificantBits.toShort())
        bytes.order(ByteOrder.BIG_ENDIAN).putLong(uuid.leastSignificantBits)
        return Memory(16).also { it.write(0, bytes.array(), 0, 16) }
    }

    private companion object {
        const val FACTORY_CLSID = "7ab36653-1796-484b-bdfa-e74f1db7c1dc"
        const val FACTORY_IID = "8e018a9d-2415-4677-bf08-794ea61f94bb"
        const val RPC_E_CHANGED_MODE = -2147417850
    }
}
