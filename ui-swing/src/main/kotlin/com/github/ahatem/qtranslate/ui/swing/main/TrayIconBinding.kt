package com.github.ahatem.qtranslate.ui.swing.main

import com.github.ahatem.qtranslate.api.core.Logger
import java.awt.AWTException
import java.awt.Image
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseListener
import java.awt.image.BaseMultiResolutionImage

/** Owns the native tray icon for the lifetime of the main window. */
internal class TrayIconBinding(private val logger: Logger) {
    private var icon: TrayIcon? = null

    /**
     * True when an icon is actually installed.
     *
     * Support and installation are different questions: `SystemTray.isSupported()` being true
     * proves the platform has a tray, not that this process put anything in it — adding the icon
     * can still fail, and then a hidden window has no way back.
     */
    val isInstalled: Boolean get() = icon != null

    fun install(images: List<Image>, listener: MouseListener) {
        if (!SystemTray.isSupported()) return
        val tray = SystemTray.getSystemTray()
        if (images.isEmpty()) {
            logger.error("Failed to load any tray icons")
            return
        }
        val candidate = TrayIcon(BaseMultiResolutionImage(*images.toTypedArray()), "QTranslate").apply {
            isImageAutoSize = true
            toolTip = "QTranslate"
            addMouseListener(listener)
        }
        try {
            tray.add(candidate)
            icon = candidate
        } catch (error: AWTException) {
            logger.error("Failed to add tray icon", error)
            icon = null
        }
    }

    fun dispose() {
        icon?.let { SystemTray.getSystemTray().remove(it) }
        icon = null
    }
}
