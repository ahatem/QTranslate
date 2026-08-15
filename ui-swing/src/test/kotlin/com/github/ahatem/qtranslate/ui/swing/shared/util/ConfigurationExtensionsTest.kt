package com.github.ahatem.qtranslate.ui.swing.shared.util

import com.formdev.flatlaf.FlatLaf
import com.formdev.flatlaf.FlatLightLaf
import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.FontConfig
import java.awt.Font
import javax.swing.UIManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the font scaling to the user's zoom and nothing else.
 *
 * The size produced here is installed as `defaultFont`, and FlatLaf derives its user scale factor
 * from that font. Anything in this calculation that reads the current scale factor therefore feeds
 * its own output back into its input, and the interface grows by the zoom percentage every time
 * the font is reapplied. That shipped once; these tests are here so it cannot ship twice.
 */
class ConfigurationExtensionsTest {

    private fun configWith(size: Int, scale: Int) = Configuration.DEFAULT.copy(
        uiFontConfig = FontConfig(name = "Dialog", size = size),
        uiScale = scale
    )

    @Test
    fun `zoom is applied exactly once`() {
        assertEquals(15, configWith(size = 12, scale = 125).scaledUiFont.size)
        assertEquals(12, configWith(size = 12, scale = 100).scaledUiFont.size)
        assertEquals(24, configWith(size = 12, scale = 200).scaledUiFont.size)
    }

    /**
     * The same configuration gives the same size however large the current default font is.
     *
     * This is the regression itself: enlarging `defaultFont` raises FlatLaf's user scale factor,
     * and the old calculation multiplied by that factor, so asking a second time gave a bigger
     * answer than asking the first time.
     *
     * The look and feel has to be installed and the font actually pushed through `updateUI` for
     * this to mean anything. Without that, `UIScale` reports a factor of 1 and the test passes
     * against the very bug it exists to catch.
     */
    @Test
    fun `size does not depend on the font currently installed`() {
        val config = configWith(size = 13, scale = 125)

        FlatLightLaf.setup()
        val first = config.scaledUiFont.size

        UIManager.put("defaultFont", Font("Dialog", Font.PLAIN, 48))
        FlatLaf.updateUI()
        // Guards the guard: if this ever stops holding, the assertion below proves nothing.
        assertTrue(
            UIScale.scale(10) > 10,
            "UIScale is not reporting a raised factor, so this test cannot detect the regression"
        )

        val second = config.scaledUiFont.size
        assertEquals(first, second, "Scaling must not read the font it is about to replace")
    }

    @Test
    fun `all three fonts scale the same way`() {
        val config = Configuration.DEFAULT.copy(
            uiFontConfig = FontConfig(name = "Dialog", size = 10),
            editorFontConfig = FontConfig(name = "Dialog", size = 10),
            editorFallbackFontConfig = FontConfig(name = "Dialog", size = 10),
            uiScale = 150
        )
        assertEquals(15, config.scaledUiFont.size)
        assertEquals(15, config.scaledEditorFont.size)
        assertEquals(15, config.scaledEditorFallbackFont.size)
    }
}
