package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.core.plugin.PluginState
import com.github.ahatem.qtranslate.core.shared.arch.ServiceType
import com.github.ahatem.qtranslate.core.shared.util.type

internal enum class PluginCategory(val serviceType: ServiceType?) {
    ALL(null),
    TRANSLATORS(ServiceType.TRANSLATOR),
    DICTIONARIES(ServiceType.DICTIONARY),
    TTS(ServiceType.TTS),
    OCR(ServiceType.OCR),
    SPELL_CHECKERS(ServiceType.SPELL_CHECKER),
    AI(null),
    OTHER(null)
}

internal object PluginPanelModel {
    fun categories(plugin: PluginState): Set<PluginCategory> {
        val serviceCategories = plugin.services.mapNotNull { service ->
            when (service.type) {
                ServiceType.TRANSLATOR -> PluginCategory.TRANSLATORS
                ServiceType.DICTIONARY -> PluginCategory.DICTIONARIES
                ServiceType.TTS -> PluginCategory.TTS
                ServiceType.OCR -> PluginCategory.OCR
                ServiceType.SPELL_CHECKER -> PluginCategory.SPELL_CHECKERS
                ServiceType.SUMMARIZER, ServiceType.REWRITER -> PluginCategory.AI
                null -> null
            }
        }.toSet()
        return serviceCategories.ifEmpty { setOf(PluginCategory.OTHER) }
    }

    fun filter(
        plugins: List<PluginState>,
        query: String,
        category: PluginCategory
    ): List<PluginState> {
        val normalizedQuery = query.trim().lowercase()
        return plugins.asSequence()
            .filter { plugin -> category == PluginCategory.ALL || category in categories(plugin) }
            .filter { plugin ->
                normalizedQuery.isEmpty() || listOf(
                    plugin.manifest.name,
                    plugin.manifest.author,
                    plugin.manifest.description,
                    plugin.manifest.id,
                    plugin.services.joinToString(" ") { it.name }
                ).any { normalizedQuery in it.lowercase() }
            }
            .sortedWith(compareBy<PluginState> { it.status.ordinal }.thenBy { it.manifest.name.lowercase() })
            .toList()
    }
}
