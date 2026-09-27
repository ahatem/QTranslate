package com.github.ahatem.qtranslate.ui.swing.shared.util

import com.formdev.flatlaf.FlatLaf
import com.formdev.flatlaf.FlatLightLaf
import java.awt.Font
import javax.swing.UIManager

/**
 * Runs [block] under FlatLaf with `defaultFont` set to [fontSize], which is how FlatLaf derives
 * its user scale factor, then puts back the look and feel and `defaultFont` that were there before.
 *
 * The restore matters: the look and feel and the scale factor are JVM-wide, so a test that leaves
 * them raised quietly runs every later test class at that scale.
 */
fun <T> withFlatLafScale(fontSize: Int, block: () -> T): T {
    val previousLaf = UIManager.getLookAndFeel()
    val previousFont = UIManager.get("defaultFont")
    try {
        FlatLightLaf.setup()
        UIManager.put("defaultFont", Font("Dialog", Font.PLAIN, fontSize))
        FlatLaf.updateUI()
        return block()
    } finally {
        UIManager.put("defaultFont", previousFont)
        UIManager.setLookAndFeel(previousLaf)
        FlatLaf.updateUI()
    }
}
