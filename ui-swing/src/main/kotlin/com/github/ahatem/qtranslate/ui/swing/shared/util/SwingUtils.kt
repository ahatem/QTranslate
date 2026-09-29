package com.github.ahatem.qtranslate.ui.swing.shared.util

import com.formdev.flatlaf.extras.FlatSVGIcon
import com.formdev.flatlaf.extras.FlatSVGIcon.ColorFilter
import com.formdev.flatlaf.extras.components.FlatButton
import com.formdev.flatlaf.util.FontUtils
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

/**
 * An icon action in FlatLaf's toolbar style. Hover, pressed, selected and focus painting all
 * belong to the look and feel, so this must not turn off [AbstractButton.isContentAreaFilled] or
 * paint its own states. Mouse clicks do not take keyboard focus from the surrounding field, but
 * the button stays reachable with Tab.
 */
fun createToolbarButton(
    icon: Icon? = null,
    tooltip: String? = null,
    onClick: (() -> Unit)? = null
): FlatButton = FlatButton().apply {
    buttonType = FlatButton.ButtonType.toolBarButton
    isFocusable = true
    isRequestFocusEnabled = false
    this.icon = icon
    toolTipText = tooltip
    onClick?.let { action -> addActionListener { action() } }
}

fun createToolbarButton(
    iconManager: IconManager,
    iconPath: String,
    size: Int,
    tooltip: String? = null,
    onClick: (() -> Unit)? = null
): FlatButton = createToolbarButton(
    (iconManager.getIcon(iconPath, size, size) as FlatSVGIcon).applyForegroundColorFilter(),
    tooltip,
    onClick
)

fun FlatSVGIcon.applyForegroundColorFilter(): FlatSVGIcon {
    return apply {
        colorFilter = ColorFilter { _: Color? ->
            if (FlatSVGIcon.isDarkLaf()) UIManager.getColor("MenuItem.foreground") else Color(0, 0, 0, 190)
        }
    }
}

/**
 * The single point where a stored [FontConfig] becomes the runtime font every text surface draws
 * with.
 *
 * [FontUtils.loadFontFamily] runs a bundled face's registered loader on demand, the first time its
 * family name is actually asked for. A default installation never asks for "Inter" by any other
 * name and "SansSerif" ([FontConfig.AUTOMATIC]) has no loader at all, so neither pays for it — but
 * an existing configuration naming "Noto Naskh Arabic", or any other bundled family, still resolves
 * correctly the first time this runs, exactly as if it had been installed eagerly at startup.
 */
fun FontConfig.toFont(): Font {
    FontUtils.loadFontFamily(this.name)
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
