package com.github.ahatem.qtranslate.ui.swing.main

import com.github.ahatem.qtranslate.core.settings.data.SelectionBehavior
import kotlin.test.Test
import kotlin.test.assertEquals

class SelectionBehaviorRouterTest {

    @Test
    fun `selection inside QTranslate is suppressed for every behavior`() {
        SelectionBehavior.entries.forEach { behavior ->
            assertEquals(SelectionAction.NONE, SelectionBehaviorRouter.decide(behavior, true), "$behavior")
        }
    }
}
