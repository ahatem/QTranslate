package com.github.ahatem.qtranslate.ui.swing.shared.util

import com.formdev.flatlaf.extras.FlatSVGIcon
import com.formdev.flatlaf.extras.FlatSVGIcon.ColorFilter
import com.formdev.flatlaf.extras.components.FlatButton
import com.formdev.flatlaf.util.SystemInfo
import com.github.ahatem.qtranslate.api.ocr.ImageData
import com.github.ahatem.qtranslate.core.settings.data.FontConfig
import com.github.ahatem.qtranslate.core.settings.data.Position
import com.github.ahatem.qtranslate.core.settings.data.Size
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import java.awt.*
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

fun getVirtualScreenBounds(): Rectangle {
    var bounds = Rectangle()
    val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
    for (gd in ge.screenDevices) {
        bounds = bounds.union(gd.defaultConfiguration.bounds)
    }
    return bounds
}

fun createButtonWithIcon(iconManager: IconManager, iconPath: String, size: Int): FlatButton {
    val icon = iconManager.getIcon(iconPath, size, size)
    return FlatButton().apply {
        this.icon = (icon as FlatSVGIcon).applyForegroundColorFilter()
        toolTipText = ""
    }
}

fun FlatSVGIcon.applyForegroundColorFilter(): FlatSVGIcon {
    return apply {
        colorFilter = ColorFilter { _: Color? ->
            if (FlatSVGIcon.isDarkLaf()) UIManager.getColor("MenuItem.foreground") else Color(0, 0, 0, 190)
        }
    }
}


fun singleKey(key: Int): KeyStroke = KeyStroke.getKeyStroke(key, 0)
fun controlKeyWith(key: Int): KeyStroke = KeyStroke.getKeyStroke(key, Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx)
fun altKeyWith(key: Int): KeyStroke = KeyStroke.getKeyStroke(key, InputEvent.ALT_DOWN_MASK)
fun shiftKeyWith(key: Int): KeyStroke = KeyStroke.getKeyStroke(key, InputEvent.SHIFT_DOWN_MASK)

fun KeyStroke.getReadableKeyStrokeText(): String {
    val sb = StringBuilder()

    if (modifiers and ActionEvent.CTRL_MASK != 0 && keyCode != KeyEvent.VK_CONTROL) {
        sb.append("Ctrl + ")
    }
    if (modifiers and ActionEvent.SHIFT_MASK != 0 && keyCode != KeyEvent.VK_SHIFT) {
        sb.append("Shift + ")
    }
    if (modifiers and ActionEvent.ALT_MASK != 0 && keyCode != KeyEvent.VK_ALT) {
        sb.append("Alt + ")
    }
    if (modifiers and ActionEvent.META_MASK != 0 && keyCode != KeyEvent.VK_META) {
        sb.append("Meta + ")
    }

    sb.append(KeyEvent.getKeyText(keyCode))
    return sb.toString()
}

fun FontConfig.toFont(): Font {
    return Font(this.name, Font.PLAIN, this.size)
}

fun Dimension.toSize(): Size = Size(width, height)
fun Size.toDimension(): Dimension = Dimension(width, height)
fun Point.toPosition(): Position = Position(x, y)
fun Position.toPoint(): Point = Point(x, y)


fun BufferedImage.toImageData(format: String): ImageData {
    val outputStream = ByteArrayOutputStream()
    ImageIO.write(this, format, outputStream)
    return ImageData(
        bytes = outputStream.toByteArray(),
        format = format,
        width = this.width,
        height = this.height
    )
}