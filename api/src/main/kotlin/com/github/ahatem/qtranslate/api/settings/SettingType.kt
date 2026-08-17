package com.github.ahatem.qtranslate.api.settings

/**
 * Defines the type of UI control to be rendered for a [@Setting][Setting].
 */
public enum class SettingType {
    /** A single-line text input field. */
    TEXT,

    /** A single-line text input field that masks its content. */
    PASSWORD,

    /** A multi-line text input area. Respect [Setting.rows] for preferred height. */
    TEXTAREA,

    /**
     * A numerical spinner. The core auto-detects whether to use an integer or
     * floating-point spinner based on the field's Kotlin type (`Int`/`Long` → integer;
     * `Double`/`Float` → decimal).
     *
     * Constrain the range with [Setting.minValue], [Setting.maxValue], and [Setting.step].
     */
    NUMBER,

    /** A checkbox for true/false values. */
    BOOLEAN,

    /** A dropdown/combobox for selecting one of a predefined set of options. */
    DROPDOWN,

    /** A component for selecting a file path. */
    FILE_PATH,

    /** A component for selecting a directory path. */
    DIRECTORY_PATH,

    /**
     * A horizontal slider for bounded numeric ranges.
     *
     * **Requires** [Setting.minValue] and [Setting.maxValue] — falls back to [NUMBER] if
     * either bound is missing. Use [Setting.step] to control tick granularity (default 0.01).
     *
     * Best suited for float fields (e.g. temperature 0.0–2.0). For integer sliders, use
     * [NUMBER] with `minValue`/`maxValue` set, which renders as a bounded spinner.
     */
    SLIDER,

    /**
     * The plugin provides a full `JComponent` via a no-argument factory method on the
     * settings class. The method name is specified in [Setting.actionMethod].
     *
     * Factory method signature: `fun createSettingPanel(): javax.swing.JComponent`
     *
     * This is the **last-resort escape hatch** for inputs that cannot be expressed with
     * any other [SettingType]. Prefer standard types whenever possible — they get
     * inline validation, group support, and conditional visibility for free.
     */
    CUSTOM_PANEL
}
