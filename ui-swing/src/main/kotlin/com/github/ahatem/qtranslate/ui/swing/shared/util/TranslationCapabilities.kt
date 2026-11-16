package com.github.ahatem.qtranslate.ui.swing.shared.util

/**
 * Which optional translation recipients the running application can actually reach.
 *
 * Settings should not offer a capability as a live choice when nothing can perform it. These are
 * resolved from the same data the runtime uses — the services currently loaded and enabled — so
 * the answer here is what a translation would find, not a separate guess.
 */
data class TranslationCapabilities(
    val summarizerAvailable: Boolean,
    val rewriterAvailable: Boolean,
) {
    companion object {
        /**
         * Used where no capability source is wired, such as a harness that only renders a panel.
         *
         * Everything is treated as available so an unwired context never disables a control the
         * real application would have offered.
         */
        val UNKNOWN = TranslationCapabilities(summarizerAvailable = true, rewriterAvailable = true)
    }
}
