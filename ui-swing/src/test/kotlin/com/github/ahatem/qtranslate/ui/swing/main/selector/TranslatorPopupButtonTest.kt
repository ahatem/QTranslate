package com.github.ahatem.qtranslate.ui.swing.main.selector

import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.core.main.domain.model.ServiceInfo
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import com.github.ahatem.qtranslate.ui.swing.shared.util.withFlatLafScale
import java.awt.ComponentOrientation
import javax.swing.SwingUtilities
import javax.swing.UIManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TranslatorPopupButtonTest {

    /** The button never reaches its icons in these tests, so an unconstructed manager is enough. */
    private fun blindIconManager(): IconManager {
        val theUnsafe = Class.forName("sun.misc.Unsafe")
            .getDeclaredField("theUnsafe")
            .apply { isAccessible = true }
            .get(null)
        val allocate = theUnsafe.javaClass.getMethod("allocateInstance", Class::class.java)
        return allocate.invoke(theUnsafe, IconManager::class.java) as IconManager
    }

    private data class Room(val expected: Int, val ltr: Int, val rtl: Int)

    /** The chevron room is already in pixels, so it must not pass through the margin FlatLaf's button border scales. */
    @Test
    fun `chevron room is scaled exactly once`() {
        val room = withFlatLafScale(fontSize = 48) {
            var result: Room? = null
            SwingUtilities.invokeAndWait {
                val button = TranslatorPopupButton(blindIconManager(), {}, textMode = true)
                button.render(
                    TranslatorSelectorState(
                        availableTranslators = listOf(
                            ServiceInfo("google", "Google Translate", null, ServiceRole.TRANSLATOR)
                        ),
                        selectedTranslatorId = "google",
                        isLoading = false
                    )
                )
                val expected = UIManager.getIcon("Table.descendingSortIcon").iconWidth + UIScale.scale(6)
                val ltr = button.insets.let { it.right - it.left }
                button.componentOrientation = ComponentOrientation.RIGHT_TO_LEFT
                val rtl = button.insets.let { it.left - it.right }
                result = Room(expected, ltr, rtl)
            }
            result!!
        }
        assertTrue(room.expected > 16, "UIScale is not reporting a raised factor, so this test proves nothing")
        assertEquals(room.expected, room.ltr, "trailing room, left to right")
        assertEquals(room.expected, room.rtl, "trailing room, right to left")
    }
}
