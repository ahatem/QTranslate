package com.github.ahatem.qtranslate.ui.swing.shared.icon

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * That every icon the code asks for exists, and that the names stay set-neutral.
 *
 * Icons are loaded by name at runtime inside a `runCatching`, so a name with no file behind it
 * produces a null icon and a blank space rather than an error. A typo, or a rename that missed a
 * call site, is therefore invisible until somebody notices a button with nothing on it. This is the
 * only thing that catches it.
 *
 * The names are semantic — what the icon means here, not what it draws. A set that renders `edit`
 * as a pencil and one that renders it as a square-with-pen both answer to `edit`, which is what
 * makes a second icon set droppable into its own folder without touching any code.
 */
class IconVocabularyTest {

    @Test
    fun `every icon the code asks for exists in the default set`() {
        val missing = (referencedNames() - fileNames()).sorted()
        if (missing.isNotEmpty()) {
            fail(
                "Referenced with no file in icons/$DEFAULT_SET, so they render blank:\n" +
                    missing.joinToString("\n") { "  $it" }
            )
        }
    }

    @Test
    fun `the default set carries nothing the code never asks for`() {
        // Dead assets are how an icon set quietly doubles in size, and every one of them is a file
        // each additional set has to supply too.
        val unused = (fileNames() - referencedNames()).sorted()
        if (unused.isNotEmpty()) {
            fail(
                "Present in icons/$DEFAULT_SET but never referenced:\n" +
                    unused.joinToString("\n") { "  $it" }
            )
        }
    }

    @Test
    fun `names are set-neutral and lowercase`() {
        val offenders = fileNames().filterNot { NAME.matches(it) }
        assertTrue(offenders.isEmpty(), "Names must be lowercase kebab-case: $offenders")

        // The point of the vocabulary. A name that describes the drawing rather than the meaning
        // cannot be honoured by a set that draws it differently.
        val drawingNames = fileNames().filter { it in NAMES_THAT_DESCRIBE_A_DRAWING }
        assertTrue(
            drawingNames.isEmpty(),
            "These name a picture rather than a meaning, so another set cannot supply them: $drawingNames"
        )
    }

    @Test
    fun `the scan reaches the source and the icons it is meant to check`() {
        // A scanning test that finds nothing passes by finding nothing.
        assertTrue(referencedNames().size >= 25, "Only ${referencedNames().size} references found; scan is broken")
        assertTrue(fileNames().size >= 25, "Only ${fileNames().size} icon files found; path is wrong")
        assertTrue("edit" in referencedNames(), "Expected the vocabulary to include 'edit'")
    }

    @Test
    fun `no call site hardcodes a set`() {
        // The point of the constants. A literal path pins one set in place and quietly ignores the
        // user's choice, which is invisible until somebody switches set and half the icons do not.
        val offenders = File("src/main/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "IconSet.kt" }
            .flatMap { f -> HARDCODED.findAll(f.readText()).map { "${f.name}: ${it.value}" } }
            .toList()
        assertTrue(offenders.isEmpty(), "Use an Icons constant instead of a literal path: $offenders")
    }

    /**
     * The vocabulary, read from the one place that defines it.
     *
     * Taken from the `IconSet.path("...")` calls rather than from string literals at the call
     * sites, because there are no longer any: every use goes through a constant in `Icons`, so
     * that object is the whole list of what a set has to supply.
     */
    private fun referencedNames(): Set<String> =
        File("src/main/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { LOOKUP.findAll(it.readText()).map { m -> m.groupValues[1] } }
            .toSet()

    private fun fileNames(): Set<String> =
        File("src/main/resources/icons/$DEFAULT_SET").listFiles()
            .orEmpty()
            .filter { it.extension == "svg" }
            .map { it.nameWithoutExtension }
            .toSet()

    private companion object {
        const val DEFAULT_SET = "lucide"
        val LOOKUP = Regex("""IconSet\.path\("([a-z0-9-]+)"\)""")
        /** Any hardcoded set path left at a call site, which the constants replaced. */
        val HARDCODED = Regex(""""icons/[a-z-]+/[a-z0-9-]+\.svg"""")
        val NAME = Regex("""[a-z0-9]+(-[a-z0-9]+)*""")

        /** Lucide's own names, kept as a guard against drifting back to them. */
        val NAMES_THAT_DESCRIBE_A_DRAWING = setOf(
            "pen-line", "ellipsis-vertical", "text-align-start", "sliders-horizontal",
            "layout-dashboard", "book-open", "arrow-left", "arrow-right", "link-2",
            "message-square", "file-scan", "scan-text", "trash", "zap", "globe",
            "palette", "package", "volume", "wand-sparkles", "triangle-alert", "unlink"
        )
    }
}
