package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.formdev.flatlaf.FlatLaf
import com.formdev.flatlaf.IntelliJTheme
import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.localization.LanguageTomlParser
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.SettingsRepository
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import com.github.ahatem.qtranslate.ui.swing.shared.theme.ThemeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.awt.Component
import java.awt.Container
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.UIManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Settings hierarchy, measured on built pages.
 *
 * These are the owner's findings turned into assertions: a group heading must be recognisable as a
 * heading rather than as a slightly different setting name, an explanation must be secondary and
 * still readable, subordinate settings must be visibly owned by the setting above them, and the
 * same rules must apply on every page rather than on the one that was looked at.
 */
class SettingsHierarchyTest {

    private val scopes = mutableListOf<CoroutineScope>()
    private var previousLaf: javax.swing.LookAndFeel? = null

    private val logger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    private val directory by lazy { Files.createTempDirectory("qtranslate-hierarchy").toFile() }

    private val localizer by lazy {
        LocalizationManager(
            Files.createTempDirectory("qtranslate-hierarchy-lang").toFile(),
            LanguageTomlParser(),
            logger,
        )
    }

    @BeforeTest
    fun rememberTheme() {
        previousLaf = UIManager.getLookAndFeel()
    }

    @AfterTest
    fun tearDown() {
        scopes.forEach { it.cancel() }
        previousLaf?.let { runCatching { UIManager.setLookAndFeel(it) } }
    }

    /**
     * Installs one of the application's own themes.
     *
     * The stock FlatLaf themes were tried first and made this test order-dependent: the whole test
     * suite shares one JVM, and a class that leaves `UIManager` carrying its own colours made the
     * measured contrast land at 1.17:1. Loading the identity theme replaces the keys the assertions
     * are about — and it is the theme the hierarchy has to look right in anyway.
     */
    private fun useTheme(light: Boolean) {
        onEdt {
            val resource = if (light) "themes/qtranslate-light.theme.json" else "themes/qtranslate-dark.theme.json"
            val stream = requireNotNull(javaClass.classLoader.getResourceAsStream(resource)) {
                "theme resource not found: $resource"
            }
            stream.use { FlatLaf.setup(IntelliJTheme.createLaf(it)) }
        }
    }

    private fun store(): SettingsStore = runBlocking {
        val repository = SettingsRepository(directory, Json { ignoreUnknownKeys = true }, logger)
        repository.updateConfiguration(Configuration.FRESH_INSTALL)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scopes += scope
        SettingsStore(repository, logger, scope, Configuration.FRESH_INSTALL)
    }

    private fun newScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }

    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun layoutTree(root: Container) {
        root.doLayout()
        root.components.forEach { if (it is Container) layoutTree(it) }
    }

    private fun descendants(root: Container): List<Component> =
        root.components.flatMap { listOf(it) + if (it is Container) descendants(it) else emptyList() }

    private fun label(panel: Container, text: String): JLabel =
        descendants(panel).filterIsInstance<JLabel>().first { it.text == text }

    private fun checkbox(panel: Container, text: String): JCheckBox =
        descendants(panel).filterIsInstance<JCheckBox>().first { it.text == text }

    private fun offsets(panel: JPanel, component: Component): Int =
        SwingUtilities.convertPoint(component, 0, 0, panel).x

    private fun builtPopups(): JPanel {
        val store = store()
        return onEdt {
            PopupsPanel(store, localizer).also {
                it.render(store.state.value)
                it.setSize(900, 900)
                layoutTree(it)
            }
        }
    }

    private fun builtGeneral(): JPanel {
        val store = store()
        val scope = newScope()
        return onEdt {
            GeneralPanel(store, localizer, scope).also {
                it.render(store.state.value)
                it.setSize(900, 900)
                layoutTree(it)
            }
        }
    }

    // ── Typography and colour ─────────────────────────────────────────────────

    @Test
    fun `a section heading outranks a sub-section heading, which outranks a setting`() {
        useTheme(light = false)
        val panel = builtPopups()

        val section = label(panel, localizer.getString("settings_window.popup_group"))
        val subSection = label(panel, localizer.getString("settings_window.translation_popup_sub"))
        val setting = label(panel, localizer.getString("settings_window.transparency"))
        val baseSize = setting.font.size

        assertTrue(section.font.isBold, "the section heading is bold")
        assertTrue(
            section.font.size > baseSize,
            "and larger than a setting's name (${section.font.size} vs $baseSize)",
        )

        assertTrue(subSection.font.isBold, "the sub-section heading is bold too")
        assertEquals(
            baseSize,
            subSection.font.size,
            "but at body size: it ranks below the section, and shrinking it is what made it look like a label",
        )
        assertEquals(
            UIManager.getColor("Label.foreground"),
            subSection.foreground,
            "the sub-section heading is text, not a dimmed label",
        )
    }

    /**
     * An explanation is set apart from its setting by type.
     *
     * The colours are deliberately not asserted here. They are derived from `UIManager`, and this
     * suite shares one JVM, so a class that leaves its own look and feel behind can make the
     * measured contrast depend on test order — measured at 1.17:1 in a full run and 4.7:1 alone,
     * with nothing about the panel differing. The colour rule is pinned where it is deterministic,
     * in `SettingsVisualsTest`, with three explicit palettes including both identity themes; what
     * this test pins is the typography, which is not environment-dependent.
     */
    @Test
    fun `an explanation is set apart from its setting by type`() {
        useTheme(light = false)
        val panel = builtPopups()
        val hintText = localizer.getString("settings_window.popup_idle_hint")
        val hint = descendants(panel)
            .filterIsInstance<SettingsTextBlock>()
            .first { it.sourceText.contains(hintText.take(24)) }
        val setting = label(panel, localizer.getString("settings_window.transparency"))

        assertTrue(hint.font.isItalic, "the explanation keeps its italics")
        assertEquals(setting.font.size - 1, hint.font.size, "and stays one point below the text it explains")
    }

    @Test
    fun `a page builds under the light theme as well as the dark one`() {
        useTheme(light = true)
        val panel = builtPopups()
        val hints = descendants(panel).filterIsInstance<SettingsTextBlock>()

        assertTrue(hints.isNotEmpty(), "the explanations are drawn by the text block under either theme")
        assertTrue(
            hints.all { it.font.isItalic },
            "and they keep the italic treatment that marks them as explanations",
        )
    }

    /**
     * A long explanation wraps, in Arabic too.
     *
     * Swing's HTML renderer does not break *italic* Arabic: measured, the same sentence wraps as
     * upright text and stays on one line inside italics or under an italic font, whatever width
     * the body declares. A long Arabic explanation was therefore drawn as one line wider than its
     * row and clipped at both ends. The panel breaks the text itself now, so what this pins is that
     * the sentence arrives broken — and complete.
     */
    @Test
    fun `a long Arabic explanation is broken into lines instead of running past the row`() {
        useTheme(light = false)
        val arabic = "الإضافات خارج «تطبيق» و«إلغاء» في هذا الحوار: تثبيت الإضافة أو تعطيلها أو " +
            "تحديثها أو إزالتها تسري فورًا، ولا يمكن التراجع عنه بإلغاء الإعدادات."
        val panel = onEdt {
            object : SettingsPanel() {
                init { addHint(arabic) }
                override fun render(state: com.github.ahatem.qtranslate.core.settings.mvi.SettingsState) = Unit
            }
        }
        val hint = onEdt { descendants(panel).filterIsInstance<SettingsTextBlock>().single() }

        assertTrue(
            onEdt { hint.renderedLines().size } >= 2,
            "the sentence is broken into lines (${onEdt { hint.renderedLines() }})",
        )
        assertEquals(
            arabic,
            onEdt { hint.sourceText },
            "and the reader still gets every word of it",
        )
        // The exact pixel measure is not asserted here: Swing scales lengths inside HTML by the
        // display scale, which the test JVM derives differently from the panel's own UIScale, so a
        // pixel budget would test the harness rather than the panel. That no line is clipped is
        // checked where it is actually visible — the Arabic settings screenshots.
    }

    // ── Grouping and nesting ─────────────────────────────────────────────────

    @Test
    fun `a sub-section's settings are indented under its heading`() {
        useTheme(light = false)
        val panel = builtPopups()

        val section = label(panel, localizer.getString("settings_window.popup_group"))
        val subSection = label(panel, localizer.getString("settings_window.translation_popup_sub"))
        val nestedSetting = label(panel, localizer.getString("settings_window.transparency"))
        val pageLevelSetting = checkbox(
            panel,
            localizer.getString("settings_window.close_popup_on_click_outside"),
        )

        assertTrue(
            offsets(panel, subSection) > offsets(panel, section),
            "the group heading is indented past the section's own rows",
        )
        assertEquals(
            offsets(panel, subSection),
            offsets(panel, nestedSetting),
            "and its settings line up with it, so the group reads as one block",
        )
        assertTrue(
            offsets(panel, pageLevelSetting) < offsets(panel, nestedSetting),
            "while the section's own settings stay out at the page edge",
        )
    }

    @Test
    fun `the group's rows are joined to it by a rail`() {
        useTheme(light = false)
        val panel = builtPopups()
        val nestedSetting = label(panel, localizer.getString("settings_window.transparency"))
        val body = ancestors(nestedSetting).first { it.javaClass.simpleName == "SubSectionBody" } as JPanel
        val width = body.width.coerceAtLeast(1)
        val height = body.height.coerceAtLeast(1)

        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            onEdt { body.paint(graphics) }
        } finally {
            graphics.dispose()
        }

        val leading = if (body.componentOrientation.isLeftToRight) {
            SettingsPanel.RAIL_X.coerceAtMost(width - 1)
        } else {
            (width - 1 - SettingsPanel.RAIL_X).coerceAtLeast(0)
        }
        val drawn = (0 until height).any { y -> image.getRGB(leading, y) != 0 }

        assertTrue(drawn, "the rail is painted on the group's leading edge, at x=$leading")
    }

    @Test
    fun `history's dependent settings are nested under the switch that enables them`() {
        useTheme(light = false)
        val panel = builtGeneral()

        val enable = checkbox(panel, localizer.getString("settings_general.enable_history"))
        val dependent = checkbox(panel, localizer.getString("settings_general.clear_history_on_exit"))

        assertTrue(
            offsets(panel, dependent) > offsets(panel, enable),
            "a setting that only applies to another one is indented under it",
        )
    }

    @Test
    fun `read-aloud is nested under the selection behaviour it depends on`() {
        useTheme(light = false)
        val panel = builtGeneral()

        val behaviour = label(panel, localizer.getString("settings_general.selection_behavior"))
        val readAloud = label(panel, localizer.getString("settings_general.selection_read_aloud"))

        assertTrue(
            offsets(panel, readAloud) > offsets(panel, behaviour),
            "the picker that only applies to one behaviour hangs under that behaviour",
        )
    }

    // ── Consistency across pages ─────────────────────────────────────────────

    /**
     * Every page draws its explanations the same way.
     *
     * The finding was about one page, but the cause was a helper shared by all of them, so the rule
     * is checked against pages the owner did not look at as well: one derived color and one font.
     */
    @Test
    fun `every page explains itself in the same secondary style`() {
        useTheme(light = false)
        val panels = builtPages()

        val expectedColor = SettingsVisuals.secondaryText()
        val expectedSize = SettingsVisuals.hintFont(JLabel().font).size
        val explanations = panels.flatMap { (name, panel) ->
            descendants(panel).filterIsInstance<SettingsTextBlock>()
                .filter { it.font.isItalic && it.font.size == expectedSize }
                .map { name to it }
        }

        assertTrue(explanations.size > 10, "the pages have explanations to compare (${explanations.size})")
        val offenders = explanations
            .filter { (_, label) -> label.foreground != expectedColor }
            .map { (name, block) -> "$name: '${block.sourceText.take(30)}' is ${block.foreground}" }

        assertTrue(
            offenders.isEmpty(),
            "every italic explanation uses the one measured secondary color:\n" + offenders.joinToString("\n"),
        )
    }

    /**
     * Pages start at the top.
     *
     * A `GridBagLayout` page with no row willing to absorb spare height centres its whole block in
     * the content area. That is what the Plugins page did when its trailing glue was dropped: the
     * heading, paragraph and button floated in the middle of the window. Only a screenshot showed
     * it, so it is pinned here for every page rather than for that one.
     */
    @Test
    fun `every page keeps its content at the top`() {
        useTheme(light = false)
        val offCenter = builtPages()
            .mapNotNull { (name, panel) ->
                val first = panel.components.firstOrNull() ?: return@mapNotNull null
                if (first.y > SettingsPanel.EDGE * 4) "$name starts at y=${first.y}" else null
            }

        assertTrue(offCenter.isEmpty(), "content is top-aligned on every page:\n" + offCenter.joinToString("\n"))
    }

    /** Every page that can be built without the OCR catalog or a network stack. */
    private fun builtPages(): List<Pair<String, JPanel>> {
        val store = store()
        val scope = newScope()
        val themeManager = ThemeManager(Files.createTempDirectory("qtranslate-hierarchy-theme").toFile(), logger)
        return onEdt {
            listOf(
                "Popups" to PopupsPanel(store, localizer),
                "Window" to LayoutPanel(store, localizer),
                "General" to GeneralPanel(store, localizer, scope),
                "Appearance" to AppearancePanel(store, themeManager, localizer),
                "Network" to NetworkPanel(store, localizer),
                "Translation" to TranslationPanel(store, localizer),
                "Languages" to LanguagesPanel(store, localizer) { emptyList() },
                "Plugins" to PluginManagerLinkPanel(localizer, onOpenPluginManager = {}),
            ).onEach { (_, panel) ->
                panel.render(store.state.value)
                panel.setSize(900, 900)
                layoutTree(panel)
            }
        }
    }

    /** Walks up from [component] to the panel, excluding the component itself. */
    private fun ancestors(component: Component): List<Container> {
        val result = mutableListOf<Container>()
        var current: Container? = component.parent
        while (current != null) {
            result += current
            current = current.parent
        }
        return result
    }
}
