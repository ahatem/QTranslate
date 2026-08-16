package com.github.ahatem.qtranslate.core.localization

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Who a translation credits, and that the credit survives being handed on.
 *
 * A single `author` string made every contributor after the first choose between erasing the
 * previous name and leaving themselves out, and the contribution history shows both: two pull
 * requests replaced the existing name outright, and another updated a translation without touching
 * the field, so that work is still credited to someone else. A list removes the choice.
 */
class LanguageTranslatorsTest {

    private val parser = LanguageTomlParser()

    private val repoRoot: File = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .firstOrNull { File(it, "languages").isDirectory && File(it, "settings.gradle.kts").isFile }
        ?: error("Could not locate the repository root from ${System.getProperty("user.dir")}")

    @Test
    fun `a list of handles is read in the order it was written`() {
        val meta = parseMeta("""translators = ["ahatem", "bovirus"]""")

        // Order is contribution order, so it must survive the parse rather than come back as a set.
        assertEquals(listOf("ahatem", "bovirus"), meta.translators)
    }

    @Test
    fun `spacing inside the list does not change who is credited`() {
        assertEquals(
            listOf("ahatem", "bovirus"),
            parseMeta("""translators = [ "ahatem" ,"bovirus" ]""").translators
        )
    }

    @Test
    fun `a file still using the old single author keeps crediting them`() {
        // Third-party and in-flight translation files predate the list. Losing their author would
        // be the very thing this change exists to prevent.
        assertEquals(listOf("John Fowler"), parseMeta("""author = "John Fowler"""").translators)
    }

    @Test
    fun `a legacy address is not carried onto the screen`() {
        // The old format was `Name <email>`. The address was never meant to be displayed, and
        // putting someone's email in the interface is not a courtesy to them.
        assertEquals(listOf("bovirus"), parseMeta("""author = "bovirus <bovirus@gmail.com>"""").translators)
    }

    @Test
    fun `the list wins when a file carries both`() {
        val meta = parseMeta(
            """
            author = "QTranslate Team"
            translators = ["ahatem", "bovirus"]
            """.trimIndent()
        )
        assertEquals(listOf("ahatem", "bovirus"), meta.translators)
    }

    @Test
    fun `a file crediting nobody says so rather than inventing a name`() {
        val meta = parseMeta("""locale = "xx-XX"""")

        assertEquals(emptyList(), meta.translators)
        assertTrue(!meta.hasTranslators, "an empty list must read as no credit, not as a placeholder")
    }

    @Test
    fun `every shipped translation credits at least one person`() {
        val files = File(repoRoot, "languages").listFiles { f -> f.extension == "toml" }!!.sorted() +
            File(repoRoot, "core/src/main/resources/localization/embedded_en.toml")

        val uncredited = files.filter { file ->
            parser.parse(file.readText()).meta?.translators.isNullOrEmpty()
        }

        assertEquals(
            emptyList(), uncredited.map { it.name },
            "a translation nobody is credited for is how the old format lost its contributors"
        )
    }

    @Test
    fun `no shipped translation still carries the retired meta fields`() {
        // version meant nothing anyone could agree on — one contributor set it to the application's
        // version — and last_updated drifted because nothing enforced it. Git knows both.
        val files = File(repoRoot, "languages").listFiles { f -> f.extension == "toml" }!!.sorted() +
            File(repoRoot, "core/src/main/resources/localization/embedded_en.toml")

        val offenders = files.filter { file ->
            file.readText().lineSequence()
                .dropWhile { it.trim() != "[meta]" }
                .drop(1)
                .takeWhile { !it.trim().startsWith("[") }
                .any { line ->
                    line.substringBefore('=').trim() in setOf("version", "last_updated", "last_update")
                }
        }

        assertEquals(emptyList(), offenders.map { it.name })
    }

    private fun parseMeta(metaBody: String): LocalizedLanguageMeta =
        parser.parse("[meta]\n$metaBody\n").meta
            ?: error("expected a [meta] section to parse")
}
