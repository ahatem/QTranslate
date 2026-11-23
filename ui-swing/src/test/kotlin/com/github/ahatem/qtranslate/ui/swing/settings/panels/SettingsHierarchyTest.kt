package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.formdev.flatlaf.FlatDarkLaf
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.ui.swing.shared.theme.ThemeManager
import com.github.ahatem.qtranslate.ui.swing.testing.SilentLogger
import com.github.ahatem.qtranslate.ui.swing.testing.onEdt
import java.awt.Container
import java.nio.file.Files
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.UIManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Settings hierarchy on built pages: explanations wrap in Arabic and every page starts at the top. */
class SettingsHierarchyTest {

    private val fixture = SettingsPanelFixture()
    private val localizer get() = fixture.localizer
    private var previousLaf: javax.swing.LookAndFeel? = null

    @BeforeTest
    fun rememberTheme() {
        previousLaf = UIManager.getLookAndFeel()
    }

    @AfterTest
    fun tearDown() {
        fixture.close()
        previousLaf?.let { runCatching { UIManager.setLookAndFeel(it) } }
    }

    /** A plain FlatLaf theme: the application's own themes register the Inter families and change other tests' font lists. */
    private fun useTheme() {
        onEdt { UIManager.setLookAndFeel(FlatDarkLaf()) }
    }

    private fun layoutTree(root: Container) {
        root.doLayout()
        root.components.forEach { if (it is Container) layoutTree(it) }
    }

    /** Swing's HTML renderer does not break italic Arabic, so the panel breaks the text itself and keeps every word. */
    @Test
    fun `a long Arabic explanation is broken into lines instead of running past the row`() {
        useTheme()
        val arabic = "الإضافات خارج «تطبيق» و«إلغاء» في هذا الحوار: تثبيت الإضافة أو تعطيلها أو " +
            "تحديثها أو إزالتها تسري فورًا، ولا يمكن التراجع عنه بإلغاء الإعدادات."
        val panel = onEdt {
            object : SettingsPanel() {
                init { addHint(arabic) }
                override fun render(state: com.github.ahatem.qtranslate.core.settings.mvi.SettingsState) = Unit
            }
        }
        val hint = onEdt { descendants(panel).filterIsInstance<JLabel>().single() }

        assertTrue(
            onEdt { hint.text.contains("<br>") },
            "the sentence is broken into lines (${onEdt { hint.text }})",
        )
        assertEquals(
            arabic,
            onEdt { hint.plainText() },
            "and the reader still gets every word of it",
        )
    }

    /** A page with no row to absorb spare height would centre its block; every page must start at the top. */
    @Test
    fun `every page keeps its content at the top`() {
        useTheme()
        val offCenter = builtPages()
            .mapNotNull { (name, panel) ->
                val first = panel.components.firstOrNull() ?: return@mapNotNull null
                if (first.y > SettingsPanel.EDGE * 4) "$name starts at y=${first.y}" else null
            }

        assertTrue(offCenter.isEmpty(), "content is top-aligned on every page:\n" + offCenter.joinToString("\n"))
    }

    /** Every page that can be built without the OCR catalog or a network stack. */
    private fun builtPages(): List<Pair<String, JPanel>> {
        val store = fixture.store(Configuration.FRESH_INSTALL)
        val scope = fixture.newScope()
        val themeManager = ThemeManager(Files.createTempDirectory("qtranslate-hierarchy-theme").toFile(), SilentLogger)
        return onEdt {
            listOf(
                "Popups" to PopupsPanel(store, localizer),
                "Window" to LayoutPanel(store, localizer),
                "General" to GeneralPanel(store, localizer, scope),
                "Appearance" to AppearancePanel(store, themeManager, localizer),
                "Network" to NetworkPanel(store, localizer),
                "Translation" to TranslationPanel(store, localizer),
                "Languages" to LanguagesPanel(store, localizer) { emptyList() },
            ).onEach { (_, panel) ->
                panel.render(store.state.value)
                panel.setSize(900, 900)
                layoutTree(panel)
            }
        }
    }
}
