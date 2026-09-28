import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReleaseClassVersionVerifierTest {
    private fun classBytes(major: Int) = byteArrayOf(
        0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte(),
        0, 0, (major ushr 8).toByte(), major.toByte()
    )

    private fun archive(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }.toByteArray()

    private fun verify(bytes: ByteArray): ReleaseClassVersionVerifier.Result {
        val file = Files.createTempFile("release-classes", ".zip").toFile()
        return try {
            file.writeBytes(bytes)
            ReleaseClassVersionVerifier.verify(file, 61)
        } finally {
            file.delete()
        }
    }

    @Test fun `Java 17 and older classes pass and non-class entries are ignored`() {
        val result = verify(archive(
            "Old.class" to classBytes(55),
            "Supported.class" to classBytes(61),
            "notes.txt" to classBytes(65)
        ))
        assertEquals(2, result.inspectedClasses)
        assertEquals(61, result.highestMajor)
        assertTrue(result.violations.isEmpty())
    }

    @Test fun `Java 21 class fails`() {
        val result = verify(archive("TooNew.class" to classBytes(65)))
        assertEquals(1, result.violations.size)
        assertTrue(result.violations.single().contains("TooNew.class"))
    }

    @Test fun `nested plugin JAR classes are checked`() {
        val plugin = archive("Plugin.class" to classBytes(65))
        val result = verify(archive("QTranslate/plugins/example.jar" to plugin))
        assertEquals(1, result.inspectedClasses)
        assertTrue(result.violations.single().contains("example.jar!/Plugin.class"))
    }

    @Test fun `optional multi release classes above Java 17 are ignored`() {
        val result = verify(archive(
            "Base.class" to classBytes(61),
            "META-INF/versions/21/Optional.class" to classBytes(65)
        ))
        assertEquals(1, result.inspectedClasses)
        assertTrue(result.violations.isEmpty())
    }
}
