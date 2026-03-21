package com.github.ahatem.qtranslate.ui.swing.shared.util

import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import java.awt.ComponentOrientation
import java.awt.Container
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.text.Bidi

fun String.isRTL(): Boolean {
    if (isBlank()) return false

    val text = trim()

    // Step 1: Use Unicode Bidi
    runCatching { Bidi(text, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT) }
        .getOrNull()
        ?.let { bidi ->
            if (!bidi.baseIsLeftToRight()) return true
        }

    // Step 2: Fallback by counting strong directional characters
    val rtlDirs = setOf(
        Character.DIRECTIONALITY_RIGHT_TO_LEFT,
        Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
        Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING,
        Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE
    )

    val ltrDirs = setOf(
        Character.DIRECTIONALITY_LEFT_TO_RIGHT,
        Character.DIRECTIONALITY_LEFT_TO_RIGHT_EMBEDDING,
        Character.DIRECTIONALITY_LEFT_TO_RIGHT_OVERRIDE
    )

    var rtl = 0
    var ltr = 0
    var i = 0
    val max = 1000

    while (i < text.length && (rtl + ltr) < max) {
        val codePoint = text.codePointAt(i)
        val dir = Character.getDirectionality(codePoint)
        when (dir) {
            in rtlDirs -> rtl++
            in ltrDirs -> ltr++
        }
        i += Character.charCount(codePoint)
    }

    if (rtl + ltr == 0) return false
    return rtl > ltr
}


fun String.copyToClipboard() {
    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
    clipboard.setContents(StringSelection(this), null)
}

/**
 * Utility function to apply component orientation based on the active language's RTL status.
 */
context(container: Container)
fun LocalizationManager.applyOrientationForActiveLanguage() {
    val orientation = if (activeLanguage?.let { getLanguageMeta(it)?.isRtl } == true) {
        ComponentOrientation.RIGHT_TO_LEFT
    } else {
        ComponentOrientation.LEFT_TO_RIGHT
    }
    container.applyComponentOrientation(orientation)
}

fun LocalizationManager.isActiveLanguageRtl(): Boolean {
    return activeLanguage?.let { getLanguageMeta(it) }?.isRtl == true
}

