package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.localization.getDisplayName
import com.github.ahatem.qtranslate.core.ocr.OcrProviderInfo
import com.github.ahatem.qtranslate.core.ocr.OcrServiceCatalog
import com.github.ahatem.qtranslate.core.settings.data.OcrPostSelectionAction
import com.github.ahatem.qtranslate.core.settings.data.OcrTranslationDestination
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsIntent
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsState
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.DisplayValueRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.SwingUtilities

/**
 * The OCR settings page: workflow, recognition defaults, text cleanup and workspace presentation.
 *
 * Provider selection writes the existing active-preset OCR slot instead of introducing a second
 * provider preference. If more than one OCR service is installed, the same choice controls the
 * Workspace and the active OCR workflow.
 */
class OcrSettingsPanel(
    private val store: SettingsStore,
    private val localizationManager: LocalizationManager,
    private val catalog: OcrServiceCatalog,
    private val scope: CoroutineScope,
    private val onOpenHotkeys: () -> Unit,
) : SettingsPanel() {

    private lateinit var postActionCombo: JComboBox<PostActionChoice>
    private lateinit var destinationCombo: JComboBox<DestinationChoice>
    private lateinit var providerCombo: JComboBox<ProviderChoice>
    private lateinit var languageCombo: JComboBox<LanguageChoice>
    private lateinit var removeLineBreaksCheck: JCheckBox
    private lateinit var annotationsCheck: JCheckBox
    private var providers: List<OcrProviderInfo> = emptyList()
    private var lastState: SettingsState? = null

    init {
        providers = catalog.providers.value
        buildUi()
        observeProviders()
    }

    private fun buildUi() {
        addSeparator(localizationManager.getString("settings_ocr.workflow_group"))

        postActionCombo = JComboBox(postActions().toTypedArray()).apply {
            renderer = DisplayValueRenderer<PostActionChoice>(text = { it?.label.orEmpty() })
            addActionListener {
                if (!isUpdatingFromState) {
                    (selectedItem as? PostActionChoice)?.let { choice ->
                        applyDraft(store) { it.copy(ocr = it.ocr.copy(postSelectionAction = choice.action)) }
                    }
                }
            }
        }
        addRow(localizationManager.getString("settings_ocr.post_action"), postActionCombo)
        addHint(localizationManager.getString("settings_ocr.post_action_hint"))

        destinationCombo = JComboBox(destinations().toTypedArray()).apply {
            renderer = DisplayValueRenderer<DestinationChoice>(text = { it?.label.orEmpty() })
            addActionListener {
                if (!isUpdatingFromState) {
                    (selectedItem as? DestinationChoice)?.let { choice ->
                        applyDraft(store) { it.copy(ocr = it.ocr.copy(translationDestination = choice.destination)) }
                    }
                }
            }
        }
        addRow(localizationManager.getString("settings_ocr.translation_destination"), destinationCombo)
        addHint(localizationManager.getString("settings_ocr.translation_destination_hint"))

        addSeparator(localizationManager.getString("settings_ocr.recognition_group"))
        providerCombo = JComboBox<ProviderChoice>().apply {
            renderer = DisplayValueRenderer<ProviderChoice>(text = { it?.label.orEmpty() })
            addActionListener {
                if (!isUpdatingFromState) {
                    val id = (selectedItem as? ProviderChoice)?.id
                    store.dispatch(SettingsIntent.UpdateServiceInActivePreset(ServiceRole.OCR, id))
                }
            }
        }
        addRow(localizationManager.getString("settings_ocr.provider"), providerCombo)
        addHint(localizationManager.getString("settings_ocr.provider_hint"))

        languageCombo = JComboBox<LanguageChoice>().apply {
            renderer = DisplayValueRenderer<LanguageChoice>(text = { it?.label.orEmpty() })
            addActionListener {
                if (!isUpdatingFromState) {
                    val tag = (selectedItem as? LanguageChoice)?.tag ?: return@addActionListener
                    applyDraft(store) { it.copy(ocr = it.ocr.copy(recognitionLanguage = tag)) }
                }
            }
        }
        addRow(localizationManager.getString("settings_ocr.language"), languageCombo)
        addHint(localizationManager.getString("settings_ocr.language_hint"))

        addSeparator(localizationManager.getString("settings_ocr.text_group"))
        removeLineBreaksCheck = addCheckbox(
            text = localizationManager.getString("settings_ocr.remove_line_breaks"),
            selected = false,
            onChange = { enabled ->
                applyDraft(store) { it.copy(ocr = it.ocr.copy(removeLineBreaks = enabled)) }
            }
        )
        addHint(localizationManager.getString("settings_ocr.remove_line_breaks_hint"))

        addSeparator(localizationManager.getString("settings_ocr.workspace_group"))
        annotationsCheck = addCheckbox(
            text = localizationManager.getString("settings_ocr.show_annotations"),
            selected = true,
            onChange = { enabled ->
                applyDraft(store) { it.copy(ocr = it.ocr.copy(showWorkspaceAnnotations = enabled)) }
            }
        )
        addHint(localizationManager.getString("settings_ocr.show_annotations_hint"))

        addSeparator(localizationManager.getString("settings_ocr.shortcuts_group"))
        addHint(localizationManager.getString("settings_ocr.shortcuts_hint"))
        val hotkeysButton = JButton(localizationManager.getString("settings_ocr.open_hotkeys"))
        hotkeysButton.addActionListener { onOpenHotkeys() }
        addRow(localizationManager.getString("settings_ocr.shortcuts_label"), hotkeysButton)

        finishLayout()
    }

    private fun observeProviders() {
        scope.launch {
            catalog.providers.collect { updated ->
                SwingUtilities.invokeLater {
                    providers = updated
                    lastState?.let(::render)
                }
            }
        }
    }

    override fun render(state: SettingsState) {
        lastState = state
        val ocr = state.workingConfiguration.ocr
        withoutTrigger {
            postActionCombo.selectedItem = postActions().firstOrNull { it.action == ocr.postSelectionAction }
            destinationCombo.selectedItem = destinations().firstOrNull {
                it.destination == ocr.translationDestination
            }
            destinationCombo.isEnabled = ocr.postSelectionAction == OcrPostSelectionAction.TRANSLATE
            renderProviders(state, ocr.recognitionLanguage)
            removeLineBreaksCheck.isSelected = ocr.removeLineBreaks
            annotationsCheck.isSelected = ocr.showWorkspaceAnnotations
        }
    }

    private fun renderProviders(state: SettingsState, recognitionLanguage: String) {
        val selectedId = state.workingConfiguration.getActivePreset()
            ?.selectedServices
            ?.get(ServiceRole.OCR)
            ?.takeIf { id -> providers.any { it.id == id } }
        val choices = buildList {
            add(ProviderChoice(null, localizationManager.getString("settings_ocr.provider_automatic")))
            providers.forEach { add(ProviderChoice(it.id, it.name)) }
        }
        val model = providerCombo.model as javax.swing.DefaultComboBoxModel<ProviderChoice>
        val previous = providerCombo.selectedItem
        model.removeAllElements()
        choices.forEach(model::addElement)
        providerCombo.selectedItem = choices.firstOrNull { it.id == selectedId }
            ?: choices.firstOrNull { it == previous }
            ?: choices.first()
        providerCombo.isEnabled = providers.isNotEmpty()

        val provider = providers.firstOrNull { it.id == (selectedId ?: providers.firstOrNull()?.id) }
        val languageChoices = buildList {
            add(
                LanguageChoice(
                    tag = "",
                    label = localizationManager.getString("settings_ocr.language_translation_source"),
                )
            )
            add(LanguageChoice(tag = LanguageCode.AUTO.tag, label = localizationManager.getString("common.auto_detect")))
            provider?.languages
                ?.filterNot { it == LanguageCode.AUTO }
                ?.distinctBy { it.tag }
                ?.sortedBy { it.tag }
                ?.forEach { language ->
                    add(
                        LanguageChoice(
                            tag = language.tag,
                            label = language.getDisplayName(
                                autoDetectLabel = localizationManager.getString("common.auto_detect")
                            ),
                        )
                    )
                }
            if (recognitionLanguage.isNotBlank() &&
                recognitionLanguage != LanguageCode.AUTO.tag &&
                none { it.tag == recognitionLanguage }
            ) {
                add(
                    LanguageChoice(
                        tag = recognitionLanguage,
                        label = localizationManager.getString(
                            "settings_ocr.language_unavailable",
                            recognitionLanguage,
                        ),
                    )
                )
            }
        }
        val languageModel = languageCombo.model as javax.swing.DefaultComboBoxModel<LanguageChoice>
        languageModel.removeAllElements()
        languageChoices.forEach(languageModel::addElement)
        languageCombo.selectedItem = languageChoices.firstOrNull { it.tag == recognitionLanguage }
            ?: languageChoices.first()
        languageCombo.isEnabled = provider != null && !provider.isLoadingLanguages
    }

    private fun postActions(): List<PostActionChoice> = listOf(
        PostActionChoice(
            OcrPostSelectionAction.SHOW_ACTIONS,
            localizationManager.getString("settings_ocr.post_show_actions"),
        ),
        PostActionChoice(
            OcrPostSelectionAction.TRANSLATE,
            localizationManager.getString("settings_ocr.post_translate"),
        ),
        PostActionChoice(
            OcrPostSelectionAction.COPY_TEXT,
            localizationManager.getString("settings_ocr.post_copy_text"),
        ),
        PostActionChoice(
            OcrPostSelectionAction.COPY_IMAGE,
            localizationManager.getString("settings_ocr.post_copy_image"),
        ),
        PostActionChoice(
            OcrPostSelectionAction.OPEN_WORKSPACE,
            localizationManager.getString("settings_ocr.post_workspace"),
        ),
    )

    private fun destinations(): List<DestinationChoice> = listOf(
        DestinationChoice(
            OcrTranslationDestination.MAIN_WINDOW,
            localizationManager.getString("settings_ocr.destination_main"),
        ),
        DestinationChoice(
            OcrTranslationDestination.QUICK_TRANSLATE,
            localizationManager.getString("settings_ocr.destination_quick"),
        ),
        DestinationChoice(
            OcrTranslationDestination.OCR_WORKSPACE,
            localizationManager.getString("settings_ocr.destination_workspace"),
        ),
    )

    internal fun postActionComboForTest(): JComboBox<*> = postActionCombo
    internal fun destinationComboForTest(): JComboBox<*> = destinationCombo
    internal fun removeLineBreaksForTest(): JCheckBox = removeLineBreaksCheck
    internal fun providerComboForTest(): JComboBox<*> = providerCombo
    internal fun languageComboForTest(): JComboBox<*> = languageCombo

    private data class PostActionChoice(val action: OcrPostSelectionAction, val label: String)
    private data class DestinationChoice(val destination: OcrTranslationDestination, val label: String)
    private data class ProviderChoice(val id: String?, val label: String)
    private data class LanguageChoice(val tag: String, val label: String)
}
