package com.github.ahatem.qtranslate.api.settings

/**
 * Defines the type of UI control to be rendered for a @Setting.
 */
enum class SettingType {
    /** A single-line text input field. */
    TEXT,

    /** A single-line text input field that masks its content. */
    PASSWORD,

    /** A multi-line text input area. */
    TEXTAREA,

    /** A numerical input, typically a spinner or formatted field. */
    NUMBER,

    /** A checkbox for true/false values. */
    BOOLEAN,

    /** A dropdown/combobox for selecting one of a predefined set of options. */
    DROPDOWN,

    /** A component for selecting a file path. */
    FILE_PATH,

    /** A component for selecting a directory path. */
    DIRECTORY_PATH
}