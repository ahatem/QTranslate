package com.github.ahatem.qtranslate.ui.swing.main

import com.github.ahatem.qtranslate.core.settings.data.SelectionBehavior
import kotlin.test.Test
import kotlin.test.assertEquals

class SelectionBehaviorRouterTest {

    @Test
    fun `off produces no automatic action`() {
        assertEquals(SelectionAction.NONE, SelectionBehaviorRouter.decide(SelectionBehavior.OFF, false))
    }

    @Test
    fun `show icon produces icon action`() {
        assertEquals(SelectionAction.SHOW_ICON, SelectionBehaviorRouter.decide(SelectionBehavior.SHOW_ICON, false))
    }

    @Test
    fun `translate produces immediate translation action`() {
        assertEquals(SelectionAction.TRANSLATE, SelectionBehaviorRouter.decide(SelectionBehavior.TRANSLATE, false))
    }

    @Test
    fun `translate and read produces read action`() {
        assertEquals(SelectionAction.TRANSLATE_AND_READ, SelectionBehaviorRouter.decide(SelectionBehavior.TRANSLATE_AND_READ, false))
    }

    @Test
    fun `selection inside QTranslate is suppressed for every behavior`() {
        SelectionBehavior.entries.forEach { behavior ->
            assertEquals(SelectionAction.NONE, SelectionBehaviorRouter.decide(behavior, true))
        }
    }
}
