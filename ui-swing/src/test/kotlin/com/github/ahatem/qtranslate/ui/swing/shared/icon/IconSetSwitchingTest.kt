package com.github.ahatem.qtranslate.ui.swing.shared.icon

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Runs against the real icon resources: a tidy path to a missing file would load a blank button. */
class IconSetSwitchingTest {

    /** Points the resolver at the repository icons folder, which the module sees one level up. */
    @BeforeTest
    fun installSets() = IconSet.installTo(java.io.File(".."))

    @AfterTest
    fun restoreDefault() = IconSet.use(IconSet.DEFAULT_ID)

    /** Mirrors what the resolver does: bundled on the classpath, everything else on disk. */
    private fun resourceExists(path: String) =
        IconSet::class.java.classLoader.getResource(path) != null ||
            java.io.File("..", path).isFile

    @Test
    fun `a partial set serves what it has and falls back for the rest`() {
        IconSet.use("heroicons")

        assertEquals("icons/heroicons/edit.svg", IconSet.path("edit"))
        assertEquals("icons/lucide/pin.svg", IconSet.path("pin"))
    }

    @Test
    fun `every name resolves to a file that is really there, in every installed set`() {
        val names = vocabulary()
        assertTrue(names.size >= 25, "Vocabulary looks wrong: ${names.size} names")

        IconSet.available().forEach { set ->
            IconSet.use(set.id)
            val broken = names.map { it to IconSet.path(it) }.filterNot { resourceExists(it.second) }
            assertTrue(
                broken.isEmpty(),
                "Set '${set.id}' resolves names to files that do not exist: $broken"
            )
        }
    }

    @Test
    fun `an unknown set falls back rather than leaving no icons`() {
        IconSet.use("does-not-exist")
        assertEquals(IconSet.DEFAULT_ID, IconSet.activeId())
        assertNotNull(IconSet::class.java.classLoader.getResource(IconSet.path("edit")))
    }

    /** The vocabulary, read from the `IconSet.path("...")` calls in the source so it cannot drift. */
    private fun vocabulary(): List<String> =
        java.io.File("src/main/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { LOOKUP.findAll(it.readText()).map { m -> m.groupValues[1] } }
            .distinct()
            .toList()

    private companion object {
        val LOOKUP = Regex("""IconSet\.path\("([a-z0-9-]+)"\)""")
    }
}
