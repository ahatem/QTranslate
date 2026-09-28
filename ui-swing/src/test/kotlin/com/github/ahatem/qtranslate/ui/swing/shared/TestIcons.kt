package com.github.ahatem.qtranslate.ui.swing.shared

import com.github.ahatem.qtranslate.core.plugin.PluginManager
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager

/**
 * An [IconManager] that loads the application's own icons, for tests that build real controls.
 *
 * Its constructor wants a whole plugin graph, which serving the bundled icons never touches, so the
 * plugin manager is allocated without running its constructor. A test that reached into it would
 * fail loudly here rather than silently change what it means.
 */
object TestIcons {
    fun iconManager(): IconManager {
        val unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
            .apply { isAccessible = true }.get(null)
        val allocate = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
        return IconManager(allocate.invoke(unsafe, PluginManager::class.java) as PluginManager)
    }
}
