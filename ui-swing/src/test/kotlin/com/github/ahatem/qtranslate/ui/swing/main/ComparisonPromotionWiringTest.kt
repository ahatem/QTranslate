package com.github.ahatem.qtranslate.ui.swing.main

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains

/**
 * Pins the #292 primary-promotion wiring: every surface that changes the
 * primary out of a multi-translator selection must promote (preserving the
 * translator set), never plain-select (which drops the old primary).
 */
class ComparisonPromotionWiringTest {
    private val sourceRoot = File("src/main/kotlin/com/github/ahatem/qtranslate/ui/swing")

    private fun source(relativePath: String): String =
        File(sourceRoot, relativePath).readText()

    @Test
    fun `comparison header promotes instead of plain selecting`() {
        assertContains(
            source("main/MainContentView.kt"),
            "SettingsIntent.PromoteTranslatorToPrimary(serviceId)"
        )
    }

    @Test
    fun `quick translate uses the same promotion semantics`() {
        assertContains(
            source("main/MainAppFrame.kt"),
            "SettingsIntent.PromoteTranslatorToPrimary(serviceId)"
        )
    }
}
