package com.github.ahatem.qtranslate.api.plugin

/**
 * A piece of text shown to the user, given as a translation key with an English fallback.
 *
 * ### How it resolves
 * The host looks for [key] in its own strings first, then in the declaring plugin's bundle, and
 * shows [fallback] if neither has it. Host strings are searched first so a plugin cannot shadow
 * the application's own UI text by declaring a colliding key.
 *
 * ### Why there is a fallback
 * String lookup returns the key itself when it misses, so a missing translation would put
 * `option.academic` on screen looking like a bug. [fallback] guarantees something readable
 * always renders, which also means a plugin that ships no translations at all still works.
 *
 * ### Plugin translations
 * A plugin translates its own text by shipping TOML files under `localization/` in its JAR —
 * `localization/en.toml`, `localization/de.toml`, and so on — using the same format as the
 * application's own language files. The host loads them through the plugin's class loader and
 * keeps them separate from its own, so keys cannot collide.
 *
 * If a plugin ships a `localization/` folder it should include `en.toml`, so translators have a
 * complete base to work from. A plugin with no folder is perfectly valid; its [fallback] values
 * are simply what everyone sees.
 *
 * @property key Translation key. Plugin-defined keys are conventionally prefixed to keep them
 *   readable, e.g. `option.style.academic`.
 * @property fallback English text shown when no bundle provides [key]. Required — never blank.
 */
data class DisplayText(
    val key: String,
    val fallback: String,
    /**
     * Values substituted into the resolved string's format placeholders.
     *
     * Text that varies — "Translated %d of %d segments" — cannot be assembled by concatenation
     * without breaking languages that order their clauses differently. Passing the pieces
     * separately lets each translation put them where that language needs them.
     */
    val args: List<String> = emptyList()
) {
    init {
        require(key.isNotBlank()) { "DisplayText.key must not be blank." }
        require(fallback.isNotBlank()) { "DisplayText.fallback must not be blank; it is what the user sees when a translation is missing." }
    }

    companion object {
        /**
         * Text with no translation key — shown exactly as given.
         *
         * For values that genuinely cannot be translated, such as a model identifier or a
         * provider's name. Prefer a real key for anything a reader would want in their own
         * language.
         */
        fun literal(text: String): DisplayText = DisplayText(key = text, fallback = text)
    }
}
