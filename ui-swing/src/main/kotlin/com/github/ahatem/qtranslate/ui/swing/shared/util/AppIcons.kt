package com.github.ahatem.qtranslate.ui.swing.shared.util

import java.awt.Image
import java.awt.Window
import javax.imageio.ImageIO

/**
 * The application's window icons, loaded once.
 *
 * Every window needs these applied explicitly. A window inherits nothing from its owner, and the
 * floating popups are deliberately owned by Swing's shared hidden frame — so without this they
 * show Java's default coffee cup wherever the platform surfaces a window icon.
 *
 * Several sizes are supplied and the platform picks: a title bar wants 16px, the task switcher
 * wants 32 or 48, and letting it downscale 128 for a 16px slot looks like mud.
 */
object AppIcons {

    private val images: List<Image> by lazy {
        listOf(16, 32, 64, 128).mapNotNull { size ->
            runCatching {
                AppIcons::class.java.classLoader
                    .getResourceAsStream("icons/app/icon-$size.png")
                    ?.use(ImageIO::read)
            }.getOrNull()
        }
    }

    /** Applies the application icons to [window]. Does nothing if the resources are missing. */
    fun applyTo(window: Window) {
        if (images.isNotEmpty()) window.iconImages = images
    }
}
