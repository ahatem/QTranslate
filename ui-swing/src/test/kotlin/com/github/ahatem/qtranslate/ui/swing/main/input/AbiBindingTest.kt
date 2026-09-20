package com.github.ahatem.qtranslate.ui.swing.main.input

import com.sun.jna.Library
import com.sun.jna.Native
import io.github.ahatem.qinput.QInput
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Proves the Java binding and the *packaged native library* agree on the ABI version.
 *
 * The source-level check in the standalone QInput repository catches a missed edit to a
 * constant, but it cannot catch a stale or mismatched *binary*: a native library built before an
 * ABI bump would report the old version while every source file looked correct. Only actually
 * loading the library does that, and this is the test that would have caught the Java constant
 * being left behind a version, because a mismatch is a hard startup failure in production.
 *
 * Skipped (not failed) when no native library is packaged for this platform/arch in the
 * resolved `io.github.ahatem:qinput` dependency.
 */
class AbiBindingTest {

    /** Mirror of the ABI probe in `qinput.h`; the tests need only this one symbol. */
    private interface AbiProbe : Library {
        fun qip_abi_version(): Int
    }

    @Test
    fun `packaged native library reports the java binding abi version`() {
        val library = resolveNativeLibrary()
        assumeTrue(library != null, "no native library available for this platform; skipped")

        val probe = Native.load(library!!.toAbsolutePath().toString(), AbiProbe::class.java)
        val nativeAbi = probe.qip_abi_version()

        assertEquals(
            QInput.ABI_VERSION,
            nativeAbi,
            "the packaged native library reports ABI " + hex(nativeAbi)
                + " but the Java binding expects " + hex(QInput.ABI_VERSION)
                + " (rebuild with `cargo build --release -p qinput-native`)"
        )
    }

    /**
     * The explicit override wins, otherwise the packaged resource for this platform is
     * extracted to a temp file so JNA can load it. Deliberately independent of
     * `NativeLibraryLoader` so the test cannot pass by sharing a bug with the code it
     * verifies.
     */
    private fun resolveNativeLibrary(): Path? {
        System.getProperty("qinput.library.path")?.takeIf { it.isNotBlank() }?.let {
            return Path.of(it).takeIf { path -> Files.isRegularFile(path) }
        }

        val resource = resourcePath() ?: return null
        val stream = javaClass.getResourceAsStream(resource) ?: return null
        stream.use { input ->
            val target = Files.createTempFile("qinput-abi-probe", librarySuffix())
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
            target.toFile().deleteOnExit()
            return target
        }
    }

    private fun resourcePath(): String? {
        val os = System.getProperty("os.name", "").lowercase(Locale.ROOT)
        val arch = when (System.getProperty("os.arch", "").lowercase(Locale.ROOT)) {
            "amd64", "x86_64" -> "x86_64"
            "aarch64", "arm64" -> "aarch64"
            else -> return null
        }
        return when {
            os.startsWith("windows") -> "/native/windows-$arch/qinput_native.dll"
            os.startsWith("mac") -> "/native/macos-$arch/libqinput_native.dylib"
            os.startsWith("linux") -> "/native/linux-$arch/libqinput_native.so"
            else -> null
        }
    }

    private fun librarySuffix(): String = when {
        System.getProperty("os.name", "").startsWith("Windows", ignoreCase = true) -> ".dll"
        System.getProperty("os.name", "").startsWith("Mac", ignoreCase = true) -> ".dylib"
        else -> ".so"
    }

    private fun hex(value: Int): String = "0x" + String.format("%08x", value)
}
