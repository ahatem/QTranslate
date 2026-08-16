package com.github.ahatem.qtranslate.core.localization

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a saved translation file looks like.
 *
 * The output is the point of the editor, not a side effect of it. A file people open by hand,
 * review in a pull request and edit in a text editor has to read like one someone wrote, so the
 * English file is reproduced structurally and only the values change.
 */
class LanguageFileWriterTest {

    private val parser = LanguageTomlParser()

    private val repoRoot: File = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .firstOrNull { File(it, "languages").isDirectory && File(it, "settings.gradle.kts").isFile }
        ?: error("Could not locate the repository root from ${System.getProperty("user.dir")}")

    private val english = File(repoRoot, "core/src/main/resources/localization/embedded_en.toml").readText()

    private val meta = LanguageFileMeta(
        name = "Vietnamese",
        nativeName = "Tiếng Việt",
        locale = "vi-VN",
        translators = listOf("vuanhvu11982", "ahatem"),
        isRtl = false
    )

    @Test
    fun `sections and comments come through in the order English declares them`() {
        val written = writeAll()

        val englishShape = shapeOf(english)
        val writtenShape = shapeOf(written)

        assertEquals(
            englishShape, writtenShape,
            "a translation must sort and read like the English file, so a diff shows only translating"
        )
    }

    @Test
    fun `a fully translated file survives a round trip`() {
        val values = parser.parse(english).entries
        val reparsed = parser.parse(writeAll(values))

        assertEquals(values, reparsed.entries)
    }

    @Test
    fun `escapes survive being written and read again`() {
        // The parser turns \n into a real newline on the way in. Writing it back without undoing
        // that would store a literal newline, breaking the file; escaping in the wrong order would
        // turn it into \\n and corrupt it a little more on every save.
        val tricky = mapOf(
            "common.ok" to "line one\nline two",
            "common.cancel" to """He said "no"""",
            "common.apply" to """a\backslash""",
            "common.save" to "tab\there"
        )
        val written = writeAll(tricky)
        val reparsed = parser.parse(written).entries

        tricky.forEach { (key, value) -> assertEquals(value, reparsed[key], "round trip changed $key") }
    }

    @Test
    fun `saving twice changes nothing the second time`() {
        // The escaping bug above shows up as a file that keeps changing on every save even when
        // nobody edited anything.
        val values = mapOf("common.ok" to "Được\nrồi", "common.cancel" to """Hủy "bỏ"""")
        val once = writeAll(values)
        val twice = LanguageFileWriter(english).write(meta, parser.parse(once).entries)

        assertEquals(once, twice)
    }

    @Test
    fun `an untranslated key is absent rather than left in English`() {
        val written = writeAll(mapOf("common.ok" to "Được"))

        assertTrue("ok = \"Được\"" in written)
        assertFalse(
            "cancel = \"Cancel\"" in written,
            "an English value under a translated language's key defeats the fallback and hides the gap"
        )
    }

    @Test
    fun `the meta block is written from the editor rather than copied from English`() {
        val written = writeAll()
        val parsed = parser.parse(written).meta!!

        assertEquals("Vietnamese", parsed.name)
        assertEquals("Tiếng Việt", parsed.nativeName)
        assertEquals("vi-VN", parsed.locale)
        assertEquals(listOf("vuanhvu11982", "ahatem"), parsed.translators)
        assertFalse(parsed.isRtl)
    }

    @Test
    fun `retired meta fields do not come back through a save`() {
        val written = writeAll()
        val metaBlock = written.lineSequence()
            .dropWhile { it.trim() != "[meta]" }
            .drop(1)
            .takeWhile { !it.trim().startsWith("[") }
            .toList()

        val keys = metaBlock.filter { "=" in it }.map { it.substringBefore('=').trim() }
        assertEquals(emptyList(), keys.filter { it in setOf("version", "last_updated", "author") })
    }

    @Test
    fun `a section with nothing translated does not leave a run of blank lines`() {
        val written = writeAll(mapOf("common.ok" to "Được"))

        assertFalse(
            Regex("(\\r?\\n){3,}").containsMatchIn(written),
            "omitted keys left the gaps their lines used to occupy"
        )
    }

    private fun writeAll(values: Map<String, String>? = null): String =
        LanguageFileWriter(english).write(meta, values ?: parser.parse(english).entries)

    /** Section headers and comments, which is the structure a reader actually perceives. */
    private fun shapeOf(text: String): List<String> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("[") || it.startsWith("#") }
            .toList()
}
