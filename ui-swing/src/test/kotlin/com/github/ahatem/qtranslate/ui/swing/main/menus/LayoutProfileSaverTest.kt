package com.github.ahatem.qtranslate.ui.swing.main.menus

import com.github.ahatem.qtranslate.core.settings.data.Density
import com.github.ahatem.qtranslate.core.settings.data.LayoutProfile
import com.github.ahatem.qtranslate.core.settings.data.RegionSlot
import com.github.ahatem.qtranslate.core.settings.data.WindowLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Saving the window's layout as a profile: the name, the replace question and the cap. */
class LayoutProfileSaverTest {

    private val window = WindowLayout(history = RegionSlot.BOTTOM, density = Density.COMPACT)

    private class Prompts(var name: String? = null, var replace: Boolean = true) : ProfilePrompts {
        val asked = mutableListOf<String>()
        var nameAsked = 0
        var fullShown: Int? = null
        override fun askName(): String? { nameAsked++; return name }
        override fun confirmReplace(name: String): Boolean { asked += name; return replace }
        override fun showFull(max: Int) { fullShown = max }
    }

    private var stored: List<LayoutProfile>? = null

    private fun saver(prompts: Prompts, existing: List<LayoutProfile> = emptyList()) =
        LayoutProfileSaver(prompts, { existing }, { window }, { stored = it })

    @Test
    fun `a new name is trimmed and saved with the window's layout`() {
        saver(Prompts("  Work  ")).saveCurrent()
        assertEquals(listOf(LayoutProfile("Work", window)), stored)
    }

    @Test
    fun `an empty name or a cancelled dialog saves nothing and asks nothing more`() {
        listOf("", "   ", null).forEach { typed ->
            val prompts = Prompts(typed)
            saver(prompts).saveCurrent()
            assertNull(stored, "typed: $typed")
            assertTrue(prompts.asked.isEmpty() && prompts.fullShown == null)
        }
    }

    @Test
    fun `a taken name asks before replacing and keeps the list order`() {
        val existing = listOf(LayoutProfile("Work", WindowLayout.DEFAULT), LayoutProfile("Home", WindowLayout.DEFAULT))

        val declined = Prompts("work", replace = false)
        saver(declined, existing).saveCurrent()
        assertNull(stored, "declining leaves the saved layout alone")
        assertEquals(listOf("Work"), declined.asked, "the question names the layout that would be lost")

        val accepted = Prompts(" WORK ", replace = true)
        saver(accepted, existing).saveCurrent()
        assertEquals(listOf(LayoutProfile("WORK", window), LayoutProfile("Home", WindowLayout.DEFAULT)), stored)
    }

    @Test
    fun `a full list says so for a new name and still replaces an existing one`() {
        val full = (1..LayoutProfile.MAX_PROFILES).map { LayoutProfile("Layout $it", WindowLayout.DEFAULT) }

        val prompts = Prompts("One more")
        saver(prompts, full).saveCurrent()
        assertNull(stored)
        assertEquals(LayoutProfile.MAX_PROFILES, prompts.fullShown)

        val replacing = Prompts("Layout 3")
        saver(replacing, full).saveCurrent()
        assertEquals(LayoutProfile.MAX_PROFILES, stored?.size)
        assertEquals(window, stored?.get(2)?.layout)
        assertNull(replacing.fullShown)
    }
}
