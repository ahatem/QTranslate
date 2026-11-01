package com.github.ahatem.qtranslate.core.workspace.translation

/**
 * Identifies the user flow that started a translation.
 */
internal enum class TranslationOrigin {
    MAIN,
    QUICK,
    OCR,

    /** Fires while the user is typing. */
    INSTANT,

    /** Backstops a panel that needs a translation to derive from. */
    INTERNAL,
}
