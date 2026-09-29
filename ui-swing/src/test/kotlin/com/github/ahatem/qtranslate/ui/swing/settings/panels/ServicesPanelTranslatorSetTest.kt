package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.dictionary.Dictionary
import com.github.ahatem.qtranslate.api.dictionary.DictionaryRequest
import com.github.ahatem.qtranslate.api.dictionary.DictionaryResponse
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.translator.TranslationRequest
import com.github.ahatem.qtranslate.api.translator.TranslationResponse
import com.github.ahatem.qtranslate.api.translator.Translator
import com.github.ahatem.qtranslate.core.localization.LanguageTomlParser
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.ServicePreset
import com.github.ahatem.qtranslate.core.settings.data.SettingsRepository
import com.github.ahatem.qtranslate.core.settings.data.disabledServiceKey
import com.github.ahatem.qtranslate.core.settings.data.isComparisonEligible
import com.github.ahatem.qtranslate.core.settings.data.secondaryTranslatorIds
import com.github.ahatem.qtranslate.core.settings.data.translatorPrimaryId
import com.github.ahatem.qtranslate.core.settings.data.translatorSetIds
import com.github.ahatem.qtranslate.core.settings.data.withServiceRoleEnabled
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsIntent
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.awt.Component
import java.awt.ComponentOrientation
import java.awt.Container
import java.awt.Dimension
import java.nio.file.Files
import javax.swing.AbstractButton
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Services page through real Swing components, the real store and real localization. */
class ServicesPanelTranslatorSetTest {
    private val scopes = mutableListOf<CoroutineScope>()

    private val logger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    private val localizer by lazy {
        LocalizationManager(Files.createTempDirectory("qtranslate-services-test").toFile(), LanguageTomlParser(), logger)
    }

    @AfterTest
    fun tearDown() {
        scopes.forEach { it.cancel() }
    }

    private class FakeTranslator(override val key: String) : Translator {
        override val name = key.replaceFirstChar { it.uppercase() }
        override val version = "test"
        override val supportedLanguages = SupportedLanguages.All
        override suspend fun translate(request: TranslationRequest): Result<TranslationResponse, ServiceError> =
            Ok(TranslationResponse(key))
    }

    private class FakeDictionary(override val key: String) : Dictionary {
        override val name = key.replaceFirstChar { it.uppercase() }
        override val version = "test"
        override val supportedLanguages = SupportedLanguages.All
        override suspend fun lookup(request: DictionaryRequest): Result<DictionaryResponse, ServiceError> =
            error("not used")
    }

    private fun services(vararg translators: String, dictionaries: List<String> = emptyList()): Map<String, Service> =
        translators.associateWith { FakeTranslator(it) } + dictionaries.associateWith { FakeDictionary(it) }

    private fun preset(id: String, primary: String?, vararg comparisons: String, dictionary: String? = null) =
        ServicePreset(
            id = id,
            name = id,
            selectedServices = mapOf(ServiceRole.TRANSLATOR to primary, ServiceRole.DICTIONARY to dictionary),
            comparisonTranslatorIds = comparisons.toList()
        )

    private fun config(vararg presets: ServicePreset, disabled: Set<String> = emptySet()) =
        Configuration.DEFAULT.copy(
            servicePresets = presets.toList(),
            activeServicePresetId = presets.first().id,
            disabledServices = disabled
        )

    private class Page(val store: SettingsStore, val panel: ServicesPanel)

    private fun page(config: Configuration, installed: Map<String, Service>): Page = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-services-store").toFile()
        val repository = SettingsRepository(directory, Json { ignoreUnknownKeys = true }, logger)
        repository.updateConfiguration(config)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scopes += scope
        val store = SettingsStore(repository, logger, scope, config)
        val panel = onEdt { ServicesPanel(store, MutableStateFlow(installed), localizer, scope) }
        onEdt { panel.render(store.state.value) }
        Page(store, panel)
    }

    private fun <T> onEdt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var result: kotlin.Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun Page.refresh() = onEdt { panel.render(store.state.value) }

    private fun Page.dispatch(intent: SettingsIntent) {
        store.dispatch(intent)
        refresh()
    }

    private fun Page.preset() = store.state.value.workingConfiguration.getActivePreset()!!

    private fun Page.click(name: String) {
        onEdt { assertNotNull(find(name) as? AbstractButton, "no button named $name").doClick() }
        refresh()
    }

    private fun Page.find(name: String): Component? = onEdt { findIn(panel, name) }

    private fun findIn(root: Component, name: String): Component? {
        if (root.name == name) return root
        return (root as? Container)?.components?.firstNotNullOfOrNull { findIn(it, name) }
    }

    private fun allIn(root: Component): List<Component> =
        listOf(root) + ((root as? Container)?.components?.flatMap(::allIn) ?: emptyList())

    /** Row ids in on-screen order, read from the name labels. */
    private fun Page.rowIds(): List<String> = onEdt {
        allIn(panel).mapNotNull { it.name }.filter { it.startsWith("translator-name:") }
            .map { it.removePrefix("translator-name:") }
    }

    private fun Page.labelText(name: String): String? = onEdt { (findIn(panel, name) as? JLabel)?.text }

    private fun Page.statusOf(id: String) = labelText("translator-status:$id")

    private fun Page.hint(): String? = onEdt {
        (findIn(panel, "translator-set-hint") as JLabel).takeIf { it.isVisible }?.text
    }

    private fun Page.button(name: String) = find(name) as JButton?

    private val primary get() = localizer.getString("settings_services.translator_primary")
    private val unavailable get() = localizer.getString("settings_services.unavailable_suffix")
    private val ready get() = localizer.getString("settings_services.translator_set_ready")
    private val needsMore get() = localizer.getString("settings_services.translator_set_needs_more")

    // ---- translator set rows ----

    @Test
    fun `localized strings resolve to real text`() {
        listOf(primary, unavailable, ready, needsMore).forEach { assertFalse(it.contains("settings_services"), it) }
    }

    @Test
    fun `configured Primary renders first with the Primary label and no make primary action`() {
        val page = page(config(preset("p", "bing", "google", "deepl")), services("google", "bing", "deepl"))

        assertEquals(listOf("bing", "google", "deepl"), page.rowIds())
        assertEquals(primary, page.statusOf("bing"))
        assertNull(page.button("translator-make-primary:bing"))
        assertNull(page.button("translator-move-up:bing"))
        assertNull(page.button("translator-move-down:bing"))
        assertNotNull(page.button("translator-remove:bing"))
        assertNotNull(page.button("translator-make-primary:google"))
    }

    @Test
    fun `make primary and status text sit quieter than the translator name`() {
        val page = page(config(preset("p", "google", "bing")), services("google", "bing"))
        onEdt {
            val nameSize = (page.find("translator-name:bing") as JLabel).font.size
            assertTrue(page.button("translator-make-primary:bing")!!.font.size < nameSize)
            assertTrue((page.find("translator-status:google") as JLabel).font.size < nameSize)
        }
    }

    @Test
    fun `comparison hint names available translators`() {
        assertEquals("Comparison uses all available translators in this set.", ready)
    }

    @Test
    fun `secondaries render in persisted order`() {
        val page = page(config(preset("p", "google", "yandex", "bing", "deepl")), services("google", "bing", "deepl", "yandex"))
        assertEquals(listOf("google", "yandex", "bing", "deepl"), page.rowIds())
    }

    @Test
    fun `the old compare with control and translator combo are gone`() {
        val page = page(config(preset("p", "google", "bing")), services("google", "bing"))
        val texts = onEdt { allIn(page.panel).mapNotNull { (it as? AbstractButton)?.text ?: (it as? JLabel)?.text } }
        assertTrue(texts.none { it.contains("Compare with", ignoreCase = true) }, texts.toString())
        assertNull(page.find("comparison-translator-chooser"))
        assertNull(page.find("service-combo:TRANSLATOR"))
        val comboRoles = onEdt { allIn(page.panel).filterIsInstance<JComboBox<*>>().mapNotNull { it.name } }
        assertEquals(
            ServiceRole.entries.filter { it != ServiceRole.TRANSLATOR }.map { "service-combo:${it.name}" }.sorted(),
            comboRoles.filter { it.startsWith("service-combo:") }.sorted()
        )
    }

    @Test
    fun `add offers only usable translators that are not configured`() {
        val installed = listOf("google", "bing", "deepl", "yandex").map { ServiceOption(it, it) }
        val model = translatorSetModel(
            config(preset("p", "google", "bing"), disabled = setOf("yandex")),
            installed
        )
        assertEquals(listOf("deepl"), model.addable.map { it.id })
    }

    @Test
    fun `add appends through the store and refreshes the rows`() {
        val page = page(config(preset("p", "google")), services("google", "bing"))
        page.dispatch(SettingsIntent.AddTranslatorToActivePreset("bing"))
        assertEquals(listOf("google", "bing"), page.rowIds())
        assertEquals(ready, page.hint())
    }

    // ---- unavailable configured members ----

    @Test
    fun `unavailable configured secondary stays visible removable and reorderable`() {
        val page = page(config(preset("p", "google", "ghost", "bing")), services("google", "bing"))

        assertEquals(listOf("google", "ghost", "bing"), page.rowIds())
        assertEquals(unavailable, page.statusOf("ghost"))
        assertNull(page.button("translator-make-primary:ghost"))
        assertTrue(page.button("translator-remove:ghost")!!.isEnabled)
        assertFalse(page.button("translator-move-up:ghost")!!.isEnabled)
        assertTrue(page.button("translator-move-down:ghost")!!.isEnabled)

        page.click("translator-move-down:ghost")
        assertEquals(listOf("google", "bing", "ghost"), page.rowIds())
        assertEquals(listOf("bing", "ghost"), page.preset().comparisonTranslatorIds)
    }

    @Test
    fun `unavailable configured Primary stays Primary and is not rewritten`() {
        val page = page(config(preset("p", "ghost", "google", "bing")), services("google", "bing"))

        assertEquals(listOf("ghost", "google", "bing"), page.rowIds())
        assertEquals("$primary · $unavailable", page.statusOf("ghost"))
        assertEquals("ghost", page.preset().translatorPrimaryId)
        assertEquals(listOf("google", "bing"), page.preset().comparisonTranslatorIds)
    }

    @Test
    fun `an unavailable member is not counted toward Comparison`() {
        val ready3 = page(config(preset("p", "google", "bing", "ghost")), services("google", "bing"))
        assertEquals(3, ready3.rowIds().size)
        assertEquals(ready, ready3.hint())

        val lonely = page(config(preset("p", "google", "ghost")), services("google", "bing"))
        assertEquals(needsMore, lonely.hint())
    }

    @Test
    fun `one effective translator shows the not ready hint and two show the ready hint`() {
        assertEquals(needsMore, page(config(preset("p", "google")), services("google", "bing")).hint())
        assertEquals(ready, page(config(preset("p", "google", "bing")), services("google", "bing")).hint())
    }

    @Test
    fun `a disabled service is configured but not effective`() {
        val page = page(
            config(preset("p", "google", "bing"), disabled = setOf("bing")),
            services("google", "bing")
        )
        assertEquals(unavailable, page.statusOf("bing"))
        assertEquals(needsMore, page.hint())
    }

    @Test
    fun `settings opening never rewrites the saved preset`() {
        val saved = config(preset("p", "ghost", "phantom", "google"))
        val page = page(saved, services("google"))
        assertEquals(saved.getActivePreset(), page.preset())
    }

    // ---- actions ----

    @Test
    fun `make primary uses the shared promotion semantics`() {
        val page = page(config(preset("p", "google", "bing", "deepl", "yandex")), services("google", "bing", "deepl", "yandex"))

        page.click("translator-make-primary:deepl")

        assertEquals("deepl", page.preset().translatorPrimaryId)
        assertEquals(listOf("bing", "google", "yandex"), page.preset().comparisonTranslatorIds)
        assertEquals(listOf("deepl", "bing", "google", "yandex"), page.rowIds())
    }

    @Test
    fun `removing a secondary keeps the others in order`() {
        val page = page(config(preset("p", "google", "bing", "deepl", "yandex")), services("google", "bing", "deepl", "yandex"))
        page.click("translator-remove:deepl")
        assertEquals("google", page.preset().translatorPrimaryId)
        assertEquals(listOf("bing", "yandex"), page.preset().comparisonTranslatorIds)
    }

    @Test
    fun `removing the Primary promotes the first remaining translator`() {
        val page = page(config(preset("p", "google", "bing", "deepl", "yandex")), services("google", "bing", "deepl", "yandex"))
        page.click("translator-remove:google")
        assertEquals("bing", page.preset().translatorPrimaryId)
        assertEquals(listOf("deepl", "yandex"), page.preset().comparisonTranslatorIds)
        assertEquals(primary, page.statusOf("bing"))
    }

    @Test
    fun `removing the last translator shows the empty state`() {
        val page = page(config(preset("p", "google")), services("google", "bing"))
        page.click("translator-remove:google")

        assertNull(page.preset().translatorPrimaryId)
        assertTrue(page.rowIds().isEmpty())
        assertEquals(localizer.getString("settings_services.translator_none"), page.labelText("translator-empty"))
        assertNull(page.hint())
        assertTrue(page.button("translator-add")!!.isEnabled)
    }

    @Test
    fun `move buttons reorder only secondaries and stop at the ends`() {
        val page = page(config(preset("p", "google", "bing", "deepl", "yandex")), services("google", "bing", "deepl", "yandex"))

        assertFalse(page.button("translator-move-up:bing")!!.isEnabled)
        assertFalse(page.button("translator-move-down:yandex")!!.isEnabled)

        page.click("translator-move-up:deepl")
        assertEquals(listOf("deepl", "bing", "yandex"), page.preset().comparisonTranslatorIds)
        page.click("translator-move-down:deepl")
        page.click("translator-move-down:deepl")
        assertEquals(listOf("bing", "yandex", "deepl"), page.preset().comparisonTranslatorIds)
        assertEquals("google", page.preset().translatorPrimaryId)
    }

    @Test
    fun `disabling the translator role keeps rows and freezes the actions`() {
        val page = page(config(preset("p", "google", "bing")), services("google", "bing"))
        page.dispatch(SettingsIntent.UpdateDraft(page.store.state.value.workingConfiguration.withServiceRoleEnabled(ServiceRole.TRANSLATOR, false)))

        assertEquals(listOf("google", "bing"), page.rowIds())
        listOf(
            "translator-add",
            "translator-remove:google",
            "translator-remove:bing",
            "translator-make-primary:bing",
            "translator-move-up:bing"
        ).forEach { assertFalse(page.button(it)!!.isEnabled, "$it must be disabled") }
        assertEquals(localizer.getString("settings_services.translator_set_disabled"), page.hint())
        assertEquals(listOf("bing"), page.preset().comparisonTranslatorIds)
        assertFalse((page.find("role-enabled:TRANSLATOR") as JCheckBox).isSelected)
    }

    @Test
    fun `row actions are focusable native buttons with tooltips`() {
        val page = page(config(preset("p", "google", "bing", "deepl")), services("google", "bing", "deepl"))
        listOf("translator-remove:bing", "translator-move-up:deepl", "translator-move-down:bing", "translator-make-primary:bing", "translator-add")
            .forEach { name ->
                val button = page.button(name)!!
                onEdt {
                    assertTrue(button.isFocusable, "$name must be reachable from the keyboard")
                    assertTrue(button.isEnabled)
                    if (name != "translator-add" && name != "translator-make-primary:bing") {
                        assertTrue(!button.toolTipText.isNullOrBlank(), "$name needs a tooltip")
                    }
                }
            }
    }

    @Test
    fun `rtl mirrors the row and its actions while the stored order stays semantic`() {
        val page = page(config(preset("p", "google", "bing", "deepl")), services("google", "bing", "deepl"))
        onEdt {
            page.panel.applyComponentOrientation(ComponentOrientation.RIGHT_TO_LEFT)
            page.panel.size = Dimension(720, 900)
            layoutTree(page.panel)
        }
        val name = page.find("translator-name:bing")!!
        val remove = page.find("translator-remove:bing")!!
        val up = page.find("translator-move-up:bing")!!
        val down = page.find("translator-move-down:bing")!!
        onEdt {
            fun x(c: Component) = SwingUtilities.convertPoint(c, 0, 0, page.panel).x
            assertTrue(x(name) > x(remove), "name sits at the leading (right) edge, actions trail on the left")
            assertTrue(x(remove) < x(down) && x(down) < x(up), "actions run right to left in reading order")
        }
        page.click("translator-move-down:bing")
        assertEquals(listOf("deepl", "bing"), page.preset().comparisonTranslatorIds)
    }

    private fun layoutTree(root: Component) {
        if (root is Container) {
            root.doLayout()
            root.components.forEach(::layoutTree)
        }
    }

    // ---- presets ----

    @Test
    fun `switching presets renders that presets set and other services`() {
        val a = preset("a", "google", "bing", dictionary = "dict-a")
        val b = preset("b", "deepl", "yandex", "bing", dictionary = null)
        val page = page(
            config(a, b),
            services("google", "bing", "deepl", "yandex", dictionaries = listOf("dict-a", "dict-b"))
        )
        assertEquals(listOf("google", "bing"), page.rowIds())
        assertEquals("dict-a", dictionaryCombo(page).selectedOption()?.id)

        page.dispatch(SettingsIntent.SetActivePreset("b"))

        assertEquals(listOf("deepl", "yandex", "bing"), page.rowIds())
        assertEquals(primary, page.statusOf("deepl"))
        assertNull(page.statusOf("google"))
        assertNull(dictionaryCombo(page).selectedOption())
        assertEquals(ready, page.hint())
    }

    // ---- other services ----

    private fun dictionaryCombo(page: Page): JComboBox<ServiceOption> {
        @Suppress("UNCHECKED_CAST")
        return page.find("service-combo:DICTIONARY") as JComboBox<ServiceOption>
    }

    private fun JComboBox<ServiceOption>.selectedOption(): ServiceOption? = onEdt { selectedItem as? ServiceOption }

    @Suppress("UNCHECKED_CAST")
    private fun JComboBox<ServiceOption>.labelAt(index: Int): String = onEdt {
        val renderer = renderer as javax.swing.ListCellRenderer<Any?>
        (renderer.getListCellRendererComponent(JList<Any?>(), getItemAt(index), index, false, false) as JLabel).text
    }

    @Test
    fun `the no selection option reads Automatic and never None`() {
        val page = page(config(preset("p", "google")), services("google", dictionaries = listOf("dict-a")))
        val combo = dictionaryCombo(page)

        val automatic = localizer.getString("settings_services.automatic")
        assertEquals(automatic, combo.labelAt(0))
        assertNotEquals(localizer.getString("common.none"), combo.labelAt(0))
        assertEquals(listOf(automatic, "Dict-a"), (0 until combo.itemCount).map { combo.labelAt(it) })
    }

    @Test
    fun `choosing Automatic clears the selection to null and leaves the role enabled`() {
        val page = page(config(preset("p", "google", dictionary = "dict-a")), services("google", dictionaries = listOf("dict-a")))
        val combo = dictionaryCombo(page)

        onEdt { combo.selectedIndex = 0 }

        assertTrue(ServiceRole.DICTIONARY in page.preset().selectedServices)
        assertNull(page.preset().selectedServices[ServiceRole.DICTIONARY])
        assertFalse(ServiceRole.DICTIONARY.disabledServiceKey() in page.store.state.value.workingConfiguration.disabledServices)
        page.refresh()
        assertTrue((page.find("role-enabled:DICTIONARY") as JCheckBox).isSelected)
        assertTrue(combo.isEnabled)
    }

    @Test
    fun `the enabled checkbox is independent from the service choice`() {
        val page = page(config(preset("p", "google", dictionary = "dict-a")), services("google", dictionaries = listOf("dict-a")))

        onEdt { (page.find("role-enabled:DICTIONARY") as JCheckBox).doClick() }
        page.refresh()

        assertTrue(ServiceRole.DICTIONARY.disabledServiceKey() in page.store.state.value.workingConfiguration.disabledServices)
        assertEquals("dict-a", page.preset().selectedServices[ServiceRole.DICTIONARY])
        assertEquals("dict-a", dictionaryCombo(page).selectedOption()?.id)
        assertFalse(dictionaryCombo(page).isEnabled)
    }

    @Test
    fun `an unavailable saved service stays represented and is not rewritten`() {
        val page = page(
            config(preset("p", "google", dictionary = "gone-plugin:default:old-dict")),
            services("google", dictionaries = listOf("dict-a"))
        )
        val combo = dictionaryCombo(page)

        val selected = combo.selectedOption()
        assertEquals("gone-plugin:default:old-dict", selected?.id)
        assertFalse(selected!!.available)
        assertEquals("old-dict ($unavailable)", combo.labelAt(combo.selectedIndex))
        assertEquals("gone-plugin:default:old-dict", page.preset().selectedServices[ServiceRole.DICTIONARY])
    }

    @Test
    fun `switching from an unavailable saved service to an available one updates the draft`() {
        val page = page(
            config(preset("p", "google", dictionary = "gone")),
            services("google", dictionaries = listOf("dict-a"))
        )
        val combo = dictionaryCombo(page)

        onEdt { combo.selectedIndex = (0 until combo.itemCount).first { combo.getItemAt(it)?.id == "dict-a" } }
        page.refresh()

        assertEquals("dict-a", page.preset().selectedServices[ServiceRole.DICTIONARY])
        assertEquals(
            listOf(localizer.getString("settings_services.automatic"), "Dict-a"),
            (0 until combo.itemCount).map { combo.labelAt(it) }
        )
    }

    @Test
    fun `working draft eligibility follows set edits without reopening settings`() {
        val installed = listOf("google", "bing")
        val page = page(config(preset("p", "google")), services("google", "bing"))
        fun eligible() = page.store.state.value.workingConfiguration.isComparisonEligible(installed)

        assertFalse(eligible())
        page.dispatch(SettingsIntent.AddTranslatorToActivePreset("bing"))
        assertTrue(eligible())
        assertEquals(ready, page.hint())
        page.dispatch(SettingsIntent.RemoveTranslatorFromActivePreset("bing"))
        assertFalse(eligible())
        assertEquals(needsMore, page.hint())
        assertEquals(listOf("google"), page.preset().translatorSetIds)
        assertTrue(page.preset().secondaryTranslatorIds.isEmpty())
    }
}
