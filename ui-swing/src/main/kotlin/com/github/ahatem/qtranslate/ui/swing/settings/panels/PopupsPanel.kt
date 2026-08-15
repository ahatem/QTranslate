package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsState
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import java.awt.BorderLayout
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

/**
 * The three floating popups: translation, dictionary, and image search.
 *
 * Split out of the former Window & Layout page. Popup behaviour is what people come back to
 * adjust, and it was buried under toolbar checkboxes.
 *
 * Every popup gets the same controls in the same order, so a setting present for one and absent
 * for another reads as a deliberate difference rather than an oversight. Two were oversights:
 * the dictionary popup had no auto-position control despite the setting existing, and the image
 * popup had no section at all.
 */
class PopupsPanel(
    private val store: SettingsStore,
    private val localizationManager: LocalizationManager
) : SettingsPanel() {

    /** One popup's controls. Held together so [render] does not have to track them individually. */
    private class PopupControls(
        val autoSize: JCheckBox?,
        val autoPosition: JCheckBox,
        val transparency: JSpinner,
        val idleTimeout: JSpinner?
    )

    private lateinit var translate: PopupControls
    private lateinit var dictionary: PopupControls
    private lateinit var images: PopupControls
    private lateinit var closeOnClickOutsideCheck: JCheckBox

    init {
        buildUI()
    }

    private fun buildUI() {
        // ── Shared ───────────────────────────────────────────────────────────
        // Ahead of the per-popup sections because it governs all three. It used to sit inside
        // the translation popup's group, which read as though it applied to that one alone.
        addSeparator(localizationManager.getString("settings_window.popup_group"))

        closeOnClickOutsideCheck = addCheckbox(
            localizationManager.getString("settings_window.close_popup_on_click_outside"),
            true
        ) { enabled ->
            applyDraft(store) { it.copy(closePopupsOnClickOutside = enabled) }
        }
        addHint(localizationManager.getString("settings_window.close_popup_on_click_outside_hint"))

        // ── Translation popup ────────────────────────────────────────────────
        addSubSeparator(localizationManager.getString("settings_window.translation_popup_sub"))
        translate = PopupControls(
            autoSize = addCheckbox(
                localizationManager.getString("settings_window.auto_size"), false
            ) { enabled -> applyDraft(store) { it.copy(isPopupAutoSizeEnabled = enabled) } },
            autoPosition = addCheckbox(
                localizationManager.getString("settings_window.auto_position"), false
            ) { enabled -> applyDraft(store) { it.copy(isPopupAutoPositionEnabled = enabled) } },
            transparency = addTransparencyRow { value ->
                applyDraft(store) { it.copy(popupTransparencyPercentage = value) }
            },
            idleTimeout = addIdleRow(Configuration.DEFAULT.popupIdleTimeoutSeconds) { value ->
                applyDraft(store) { it.copy(popupIdleTimeoutSeconds = value) }
            }
        )
        addHint(localizationManager.getString("settings_window.popup_idle_hint"))

        // ── Dictionary popup ─────────────────────────────────────────────────
        addSubSeparator(localizationManager.getString("settings_window.dict_popup_sub"))
        dictionary = PopupControls(
            // No auto-size: the dictionary popup sizes itself to the definition it holds.
            autoSize = null,
            autoPosition = addCheckbox(
                localizationManager.getString("settings_window.auto_position"), false
            ) { enabled -> applyDraft(store) { it.copy(isQuickDictionaryAutoPositionEnabled = enabled) } },
            transparency = addTransparencyRow { value ->
                applyDraft(store) { it.copy(quickDictionaryTransparencyPercentage = value) }
            },
            idleTimeout = addIdleRow(Configuration.DEFAULT.quickDictionaryIdleTimeoutSeconds) { value ->
                applyDraft(store) { it.copy(quickDictionaryIdleTimeoutSeconds = value) }
            }
        )
        addHint(localizationManager.getString("settings_window.popup_idle_hint"))

        // ── Image search popup ───────────────────────────────────────────────
        addSubSeparator(localizationManager.getString("settings_window.image_popup_sub"))
        images = PopupControls(
            autoSize = null,
            autoPosition = addCheckbox(
                localizationManager.getString("settings_window.auto_position"), false
            ) { enabled -> applyDraft(store) { it.copy(isImageSearchAutoPositionEnabled = enabled) } },
            transparency = addTransparencyRow { value ->
                applyDraft(store) { it.copy(imageSearchTransparencyPercentage = value) }
            },
            // No idle timeout, deliberately: a grid of pictures is compared rather than read, and
            // one that vanished mid-comparison would be worse than one closed by hand.
            idleTimeout = null
        )
        addHint(localizationManager.getString("settings_window.image_popup_no_idle_hint"))

        finishLayout()
    }

    private fun addTransparencyRow(onChange: (Int) -> Unit): JSpinner {
        val spinner = JSpinner(SpinnerNumberModel(TRANSPARENCY_MIN, TRANSPARENCY_MIN, TRANSPARENCY_MAX, 5)).apply {
            addChangeListener { if (!isUpdatingFromState) onChange(value as Int) }
        }
        addRow(
            localizationManager.getString("settings_window.transparency"),
            JPanel(BorderLayout(4, 0)).apply {
                isOpaque = false
                add(spinner, BorderLayout.LINE_START)
                add(JLabel("%"), BorderLayout.CENTER)
            }
        )
        return spinner
    }

    private fun addIdleRow(initial: Int, onChange: (Int) -> Unit): JSpinner {
        val spinner = JSpinner(SpinnerNumberModel(initial, IDLE_MIN, IDLE_MAX, 1)).apply {
            addChangeListener { if (!isUpdatingFromState) onChange(value as Int) }
        }
        addRow(
            localizationManager.getString("settings_window.popup_idle_timeout"),
            JPanel(BorderLayout(8, 0)).apply {
                isOpaque = false
                add(spinner, BorderLayout.LINE_START)
                add(JLabel(localizationManager.getString("settings_window.seconds_unit")), BorderLayout.CENTER)
            }
        )
        return spinner
    }

    override fun render(state: SettingsState) {
        val c = state.workingConfiguration
        withoutTrigger {
            closeOnClickOutsideCheck.isSelected = c.closePopupsOnClickOutside

            translate.autoSize?.isSelected = c.isPopupAutoSizeEnabled
            translate.autoPosition.isSelected = c.isPopupAutoPositionEnabled
            translate.transparency.value = c.popupTransparencyPercentage.coerceIn(TRANSPARENCY_MIN, TRANSPARENCY_MAX)
            translate.idleTimeout?.value = c.popupIdleTimeoutSeconds.coerceIn(IDLE_MIN, IDLE_MAX)

            dictionary.autoPosition.isSelected = c.isQuickDictionaryAutoPositionEnabled
            dictionary.transparency.value =
                c.quickDictionaryTransparencyPercentage.coerceIn(TRANSPARENCY_MIN, TRANSPARENCY_MAX)
            dictionary.idleTimeout?.value = c.quickDictionaryIdleTimeoutSeconds.coerceIn(IDLE_MIN, IDLE_MAX)

            images.autoPosition.isSelected = c.isImageSearchAutoPositionEnabled
            images.transparency.value =
                c.imageSearchTransparencyPercentage.coerceIn(TRANSPARENCY_MIN, TRANSPARENCY_MAX)
        }
    }

    private companion object {
        const val TRANSPARENCY_MIN = 5
        const val TRANSPARENCY_MAX = 50
        const val IDLE_MIN = 2
        const val IDLE_MAX = 60
    }
}
