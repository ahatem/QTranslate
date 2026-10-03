package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.formdev.flatlaf.util.FontUtils
import java.awt.Font

/** Keeps shortcut tokens monospaced, with the normal composite UI font for unsupported text. */
internal fun keyChipFont(text: String, preferredMonoFont: Font, uiFont: Font): Font =
    if (preferredMonoFont.canDisplayUpTo(text) == -1) {
        preferredMonoFont
    } else {
        FontUtils.getCompositeFont(uiFont.family, preferredMonoFont.style, preferredMonoFont.size)
    }
