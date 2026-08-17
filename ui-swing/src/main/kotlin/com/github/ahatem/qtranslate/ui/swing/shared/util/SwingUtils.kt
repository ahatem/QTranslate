package com.github.ahatem.qtranslate.ui.swing.shared.util

import com.formdev.flatlaf.extras.FlatSVGIcon
import com.formdev.flatlaf.extras.FlatSVGIcon.ColorFilter
import com.formdev.flatlaf.extras.components.FlatButton
import com.github.ahatem.qtranslate.api.ocr.ImageData
import com.github.ahatem.qtranslate.core.settings.data.FontConfig
import com.github.ahatem.qtranslate.core.settings.data.Position
import com.github.ahatem.qtranslate.core.settings.data.Size
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import java.awt.*
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import javax.swing.*

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
/**
 * Removes this component's border so that a look-and-feel change cannot bring it back.
 *
 * `border = null` looks like it does the same thing, but [javax.swing.LookAndFeel.installBorder]
 * reinstalls the look-and-feel default whenever the current border is `null` or a `UIResource` —
 * and every component's `updateUI()` runs that on a theme change. Scroll panes and split panes
 * that were built borderless would suddenly draw a frame around themselves the first time the
 * user switched themes. An empty border is neither `null` nor a `UIResource`, so it survives.
 */
fun JComponent.clearBorder() {
    border = BorderFactory.createEmptyBorder()
}
