package com.github.ahatem.qtranslate.plugins.csv

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CsvIndexTest {

    private val directory = Files.createTempDirectory("csv-services-test").toFile()

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private fun csv(name: String, content: String): File =
        File(directory, name).apply { writeText(content) }

    @Test
    fun `reads a headed file by column name`() {
        val file = csv(
            "glossary.csv",
            """
            term,meaning
            DNS,Domain Name System
            TLS,Transport Layer Security
            """.trimIndent()
        )

        val index = CsvIndex.read(file, CsvSettings(termColumn = "term", definitionColumn = "meaning"))

        assertEquals(2, index.size)
        assertEquals("Domain Name System", index.lookup("DNS").single().definition)
    }

    @Test
    fun `reads a headerless file by column number`() {
        // 1-based, because a spreadsheet's first column is column 1 to everyone but a programmer.
        val file = csv("plain.csv", "DNS,Domain Name System\nTLS,Transport Layer Security")

        val index = CsvIndex.read(file, CsvSettings(termColumn = "1", definitionColumn = "2"))

        assertEquals(2, index.size)
        assertEquals("Transport Layer Security", index.lookup("TLS").single().definition)
    }

    @Test
    fun `a quoted definition containing the delimiter survives`() {
        // The common case for a comma-separated file of prose definitions.
        val file = csv(
            "quoted.csv",
            """
            term,meaning
            REST,"An architectural style, not a protocol"
            """.trimIndent()
        )

        val index = CsvIndex.read(file, CsvSettings(termColumn = "term", definitionColumn = "meaning"))

        assertEquals("An architectural style, not a protocol", index.lookup("REST").single().definition)
    }

    @Test
    fun `a doubled quote inside a quoted cell is one literal quote`() {
        // Escaped rather than raw strings: the content is mostly quotes, which a raw string in
        // Kotlin cannot hold without more ceremony than it saves.
        val file = csv(
            "escaped.csv",
            "term,meaning\nidiom,\"They call it \"\"shipping\"\"\""
        )

        val index = CsvIndex.read(file, CsvSettings(termColumn = "term", definitionColumn = "meaning"))

        assertEquals("They call it \"shipping\"", index.lookup("idiom").single().definition)
    }

    @Test
    fun `lookup ignores case unless asked not to`() {
        val file = csv("case.csv", "DNS,Domain Name System")

        val insensitive = CsvIndex.read(file, CsvSettings(caseSensitive = false))
        assertEquals(1, insensitive.lookup("dns").size)

        val sensitive = CsvIndex.read(file, CsvSettings(caseSensitive = true))
        assertTrue(sensitive.lookup("dns").isEmpty())
        assertEquals(1, sensitive.lookup("DNS").size)
    }

    @Test
    fun `a term appearing more than once keeps every definition`() {
        // A glossary with one word used differently in two contexts should show both.
        val file = csv(
            "repeated.csv",
            """
            term,meaning
            bank,The side of a river
            bank,A place that holds money
            """.trimIndent()
        )

        val index = CsvIndex.read(file, CsvSettings(termColumn = "term", definitionColumn = "meaning"))

        assertEquals(2, index.lookup("bank").size)
    }

    @Test
    fun `rows missing the columns being read are skipped, not fatal`() {
        // A stray blank line or trailing note should not cost the user the rest of the file.
        val file = csv(
            "ragged.csv",
            """
            term,meaning
            DNS,Domain Name System
            incomplete
            ,orphan definition
            TLS,Transport Layer Security
            """.trimIndent()
        )

        val index = CsvIndex.read(file, CsvSettings(termColumn = "term", definitionColumn = "meaning"))

        assertEquals(2, index.size)
        assertEquals(1, index.lookup("TLS").size)
    }

    @Test
    fun `a tab-separated file is read when the delimiter says so`() {
        // Typed into a text field, so it arrives as the two characters backslash-t.
        val file = csv("tabs.tsv", "term\tmeaning\nDNS\tDomain Name System")

        val index = CsvIndex.read(
            file,
            CsvSettings(delimiter = "\\t", termColumn = "term", definitionColumn = "meaning")
        )

        assertEquals("Domain Name System", index.lookup("DNS").single().definition)
    }

    @Test
    fun `the notes column is optional and read when named`() {
        val file = csv(
            "notes.csv",
            """
            term,meaning,source
            DNS,Domain Name System,RFC 1035
            """.trimIndent()
        )

        val withNotes = CsvIndex.read(
            file,
            CsvSettings(termColumn = "term", definitionColumn = "meaning", notesColumn = "source")
        )
        assertEquals("RFC 1035", withNotes.lookup("DNS").single().note)

        val withoutNotes = CsvIndex.read(
            file,
            CsvSettings(termColumn = "term", definitionColumn = "meaning")
        )
        assertNull(withoutNotes.lookup("DNS").single().note)
    }

    @Test
    fun `a column that cannot be found yields nothing rather than the wrong column`() {
        // The wrong delimiter makes every header name unfindable at once. Falling back to the
        // first column would read it as both term and definition, producing rows whose two halves
        // are the same string — which looks like a working file until someone reads it.
        val file = csv("semicolons.csv", "term;meaning\nDNS;Domain Name System")

        val index = CsvIndex.read(
            file,
            CsvSettings(delimiter = ",", termColumn = "term", definitionColumn = "meaning")
        )

        assertEquals(0, index.size)
    }

    @Test
    fun `a column number past the end of the file yields nothing`() {
        val file = csv("narrow.csv", "DNS,Domain Name System")

        val index = CsvIndex.read(file, CsvSettings(termColumn = "1", definitionColumn = "5"))

        assertEquals(0, index.size)
    }

    @Test
    fun `an empty file yields an empty index rather than failing`() {
        val index = CsvIndex.read(csv("empty.csv", ""), CsvSettings())
        assertEquals(0, index.size)
        assertTrue(index.lookup("anything").isEmpty())
    }

    @Test
    fun `surrounding whitespace does not stop a term matching`() {
        val file = csv("spaced.csv", "term,meaning\n  DNS  ,  Domain Name System  ")

        val index = CsvIndex.read(file, CsvSettings(termColumn = "term", definitionColumn = "meaning"))

        assertEquals("Domain Name System", index.lookup("DNS").single().definition)
    }
}
