package com.github.ahatem.qtranslate.ui.swing.shared.icon

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * That choosing a set actually changes which file is loaded, and that a gap in it is covered.
 *
 * Run against the real resources rather than a stub, because the thing worth checking is not the
 * `if` in [IconSet.path] but whether the files it names are genuinely on the classpath. A resolver
 * that returns a tidy path to nothing loads a blank button, which is the failure this whole
 * arrangement exists to avoid.
 */
class IconSetSwitchingTest {

    /**
     * Points the resolver at the repository's own icons folder.
     *
     * At runtime that folder is staged into the data directory beside languages and themes; from a
     * test the repository copy is the same content, and the module runs one level below the root.
     * Without this only the bundled default resolves and every other set looks empty.
     */
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

        // Heroicons publishes this one, so it wins.
        assertEquals("icons/heroicons/edit.svg", IconSet.path("edit"))
        // It publishes no push-pin, so Lucide covers it rather than leaving a blank.
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
    fun `switching sets actually changes something`() {
        // Guards the case where a set is registered but its folder is empty or misnamed: every
        // lookup would fall back and the setting would appear to do nothing at all.
        val names = vocabulary()
        IconSet.use(IconSet.DEFAULT_ID)
        val baseline = names.associateWith { IconSet.path(it) }

        IconSet.available()
            .filter { it.id != IconSet.DEFAULT_ID }
            .forEach { set ->
                IconSet.use(set.id)
                val changed = names.count { IconSet.path(it) != baseline[it] }
                assertTrue(changed > 0, "Choosing '${set.id}' changed no icon at all")
            }
    }

    @Test
    fun `the sets on offer are the ones that hold icons`() {
        val offered = IconSet.available()
        assertTrue(offered.any { it.id == IconSet.DEFAULT_ID }, "The default set must always be offered")

        // The invariant, rather than a list of which sets happen to be populated today: an earlier
        // version of this named phosphor and heroicons as absent and started failing the moment
        // they were filled in, which is a test describing a moment instead of a rule.
        offered.forEach { set ->
            val names = vocabulary()
            val present = names.count { resourceExists("icons/${set.id}/$it.svg") }
            assertTrue(present > 0, "Set '${set.id}' is offered but holds no icons at all")
        }
    }

    @Test
    fun `an unknown set falls back rather than leaving no icons`() {
        IconSet.use("does-not-exist")
        assertEquals(IconSet.DEFAULT_ID, IconSet.activeId())
        assertNotNull(IconSet::class.java.classLoader.getResource(IconSet.path("edit")))
    }

    /**
     * The vocabulary, read from the `IconSet.path("...")` calls that define it.
     *
     * Read rather than listed, so this cannot drift from the real list, and taken from the source
     * rather than exposed as test-only API on [IconSet], which would put a hole in production code
     * for the sake of a test.
     */
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
