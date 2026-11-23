package com.github.ahatem.qtranslate.core.settings.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Saving a layout under a name: trimming, replacing, and the cap. */
class LayoutProfilesTest {

    private val compact = WindowLayout(history = RegionSlot.TOP, languages = RegionSlot.TOP, shareRows = true)
    private val focus = WindowLayout(services = RegionSlot.HIDDEN, showStatusBar = false)

    private fun profiles(count: Int) = (1..count).map { LayoutProfile("Layout $it", WindowLayout.DEFAULT) }

    @Test
    fun `a name is trimmed and an empty one cancels`() {
        assertEquals("Work", cleanProfileName("  Work  "))
        assertNull(cleanProfileName("   "))
        assertNull(cleanProfileName(""))
        assertNull(cleanProfileName(null))
        assertNull(emptyList<LayoutProfile>().withProfileSaved("   ", compact))
    }

    @Test
    fun `a new name is appended after the saved layouts`() {
        val saved = listOf(LayoutProfile("Work", compact)).withProfileSaved("  Reading ", focus)
        assertEquals(listOf(LayoutProfile("Work", compact), LayoutProfile("Reading", focus)), saved)
    }

    @Test
    fun `saving over a name replaces that layout in place whatever the case`() {
        val before = listOf(LayoutProfile("Work", compact), LayoutProfile("Reading", compact))
        val after = before.withProfileSaved("work", focus)
        assertEquals(listOf(LayoutProfile("work", focus), LayoutProfile("Reading", compact)), after)
        assertEquals("Work", before.profileNamed(" WORK ")?.name)
        assertNull(before.profileNamed("Other"))
    }

    @Test
    fun `a full list takes no new name but still replaces an existing one`() {
        val full = profiles(LayoutProfile.MAX_PROFILES)
        assertTrue(full.isFull)
        assertFalse(profiles(LayoutProfile.MAX_PROFILES - 1).isFull)

        assertNull(full.withProfileSaved("One more", compact), "the caller says the list is full")
        val replaced = full.withProfileSaved("layout 7", compact)
        assertEquals(LayoutProfile.MAX_PROFILES, replaced?.size)
        assertEquals(compact, replaced?.get(6)?.layout)
    }

    @Test
    fun `the first layout equal to the window matches and any difference matches none`() {
        val list = listOf(LayoutProfile("A", compact), LayoutProfile("B", compact), LayoutProfile("C", focus))
        assertEquals("A", list.profileMatching(compact)?.name)
        assertEquals("C", list.profileMatching(focus)?.name)
        assertNull(list.profileMatching(compact.copy(density = Density.COMPACT)))
        assertNull(list.profileMatching(WindowLayout.DEFAULT))
    }

    @Test
    fun `the write path keeps at most the cap`() {
        val saved = Configuration.DEFAULT.withLayoutProfiles(profiles(LayoutProfile.MAX_PROFILES + 3))
        assertEquals(LayoutProfile.MAX_PROFILES, saved.layoutProfiles.size)
    }

    @Test
    fun `renaming keeps the layout and refuses a name another layout has`() {
        val list = listOf(LayoutProfile("Work", compact), LayoutProfile("Reading", focus))

        assertEquals(
            listOf(LayoutProfile("Office", compact), LayoutProfile("Reading", focus)),
            list.withProfileRenamed("work", "  Office "),
        )
        assertEquals(
            listOf(LayoutProfile("WORK", compact), LayoutProfile("Reading", focus)),
            list.withProfileRenamed("Work", "WORK"),
            "a layout may change the spelling of its own name",
        )
        assertNull(list.withProfileRenamed("Work", " reading "), "another layout already has that name")
        assertNull(list.withProfileRenamed("Work", "   "), "an empty name cancels")
        assertNull(list.withProfileRenamed("Missing", "Office"))
    }

    @Test
    fun `removing drops only the named layout`() {
        val list = listOf(LayoutProfile("Work", compact), LayoutProfile("Reading", focus))
        assertEquals(listOf(LayoutProfile("Reading", focus)), list.withProfileRemoved(" work "))
        assertEquals(list, list.withProfileRemoved("Missing"))
    }

    @Test
    fun `a stored list longer than the cap is read back capped`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val many = profiles(LayoutProfile.MAX_PROFILES + 4)
        val stored = json.encodeToString(Configuration.serializer(), Configuration.DEFAULT.copy(layoutProfiles = many))

        val loaded = json.decodeFromString(Configuration.serializer(), stored)

        assertEquals(many.take(LayoutProfile.MAX_PROFILES), loaded.layoutProfiles)
    }
}
