package com.github.ahatem.qtranslate.core.localization

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Who a translation credits, and that the credit survives being handed on. */
class LanguageTranslatorsTest {

    private val parser = LanguageTomlParser()

    private val repoRoot: File = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .firstOrNull { File(it, "languages").isDirectory && File(it, "settings.gradle.kts").isFile }
        ?: error("Could not locate the repository root from ${System.getProperty("user.dir")}")

    @Test
    fun `a list of handles is read in the order it was written`() {
        // Order is contribution order, so it must survive the parse rather than come back as a set.
        val spellings = listOf(
            "plain" to """translators = ["ahatem", "bovirus"]""",
            "spacing inside the list" to """translators = [ "ahatem" ,"bovirus" ]""",
        )

        for ((label, body) in spellings) {
            assertEquals(listOf("ahatem", "bovirus"), parseMeta(body).translators, label)
        }
    }

    @Test
    fun `a file still using the old single author keeps crediting them`() {
        // Third-party translation files predate the list; their author must not be lost.
        assertEquals(listOf("John Fowler"), parseMeta("""author = "John Fowler"""").translators)
    }

    @Test
    fun `a legacy address is not carried onto the screen`() {
        // The old format was `Name <email>`; the address must never reach the interface.
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

    private fun parseMeta(metaBody: String): LocalizedLanguageMeta =
        parser.parse("[meta]\n$metaBody\n").meta
            ?: error("expected a [meta] section to parse")
}
