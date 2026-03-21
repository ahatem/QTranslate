package com.github.ahatem.qtranslate.ui.swing.shared.icon

import com.formdev.flatlaf.extras.FlatSVGIcon
import com.formdev.flatlaf.util.ScaledImageIcon
import com.github.ahatem.qtranslate.core.plugin.PluginManager
import java.net.URL
import javax.swing.Icon
import javax.swing.ImageIcon

class IconManager(private val pluginManager: PluginManager) {

    private val iconCache = mutableMapOf<String, Icon>()

    fun getIcon(path: String, width: Int, height: Int): Icon {
        val cacheKey = "app:$path:$width:$height"
        return iconCache.getOrPut(cacheKey) {
            createIcon(path, width, height, this::class.java.classLoader)
        }
    }

    fun getIcon(serviceId: String, path: String, width: Int, height: Int): Icon {
        val cacheKey = "$serviceId:$path:$width:$height"
        return iconCache.getOrPut(cacheKey) {
            val pluginLoader = pluginManager.getPluginClassLoaderForService(serviceId)
            createIcon(path, width, height, pluginLoader ?: this::class.java.classLoader)
        }
    }

    private fun createIcon(path: String, width: Int, height: Int, loader: ClassLoader): Icon {
        return when {
            path.endsWith(".svg", true) -> loadSvgIcon(path, width, height, loader)
            path.endsWith(".png", true) || path.endsWith(".jpg", true) || path.endsWith(".gif", true) ->
                loadRasterIcon(path, width, height, loader)

            else -> getMissingIcon(width, height)
        }
    }

    private fun loadSvgIcon(path: String, width: Int, height: Int, loader: ClassLoader): Icon {
        val icon = FlatSVGIcon(path, width, height, loader)
        return if (icon.iconWidth > 0) icon else getMissingIcon(width, height)
    }

    private fun loadRasterIcon(path: String, width: Int, height: Int, loader: ClassLoader): Icon {
        val resource: URL? = loader.getResource(path)
        if (resource != null) {
            val image = ImageIcon(resource)
            if (image.iconWidth > 0 && image.iconHeight > 0) {
                return ScaledImageIcon(image, width, height)
            }
        }
        return getMissingIcon(width, height)
    }

    private fun getMissingIcon(width: Int, height: Int): Icon {
        val loader = this::class.java.classLoader
        val missing = FlatSVGIcon("ui/icons/missing_icon.svg", width, height, loader)
        return if (missing.iconWidth > 0) missing else FlatSVGIcon(
            "com/formdev/flatlaf/extras/icons/errorCircle.svg",
            width,
            height,
            loader
        )
    }
}