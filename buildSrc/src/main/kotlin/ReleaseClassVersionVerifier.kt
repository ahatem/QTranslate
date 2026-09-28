import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Checks the bytecode shipped in release archives, including JARs inside the portable ZIP. */
object ReleaseClassVersionVerifier {
    data class Result(val highestMajor: Int, val inspectedClasses: Int, val violations: List<String>)

    fun verify(archive: File, maximumMajor: Int): Result {
        require(archive.isFile) { "Release artifact is missing: $archive" }
        var highestMajor = 0
        var inspectedClasses = 0
        val violations = mutableListOf<String>()
        val maximumJava = maximumMajor - 44

        fun scan(input: InputStream, location: String) {
            val zip = ZipInputStream(input)
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val entryLocation = "$location!/${entry.name}"
                when {
                    entry.name.endsWith(".class", ignoreCase = true) -> {
                        val header = zip.readNBytes(8)
                        check(header.size == 8 && header[0] == 0xCA.toByte() &&
                            header[1] == 0xFE.toByte() && header[2] == 0xBA.toByte() &&
                            header[3] == 0xBE.toByte()) { "Invalid class file: $entryLocation" }
                        val major = ((header[6].toInt() and 0xff) shl 8) or (header[7].toInt() and 0xff)
                        val optionalVersion = Regex("(?:^|/)META-INF/versions/(\\d+)/")
                            .find(entry.name)?.groupValues?.get(1)?.toInt()
                        if (optionalVersion == null || optionalVersion <= maximumJava) {
                            inspectedClasses++
                            highestMajor = maxOf(highestMajor, major)
                            if (major > maximumMajor) violations += "$entryLocation (major $major)"
                        }
                    }
                    entry.name.endsWith(".jar", ignoreCase = true) ||
                        entry.name.endsWith(".zip", ignoreCase = true) -> {
                        scan(zip.readBytes().inputStream(), entryLocation)
                    }
                }
                zip.closeEntry()
            }
        }

        archive.inputStream().use { scan(it, archive.name) }
        return Result(highestMajor, inspectedClasses, violations)
    }
}
