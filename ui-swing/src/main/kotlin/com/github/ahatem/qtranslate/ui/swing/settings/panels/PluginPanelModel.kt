package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.core.plugin.PluginState
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.core.shared.util.roles

internal enum class PluginCategory(val serviceRole: ServiceRole?) {
    ALL(null),
    TRANSLATORS(ServiceRole.TRANSLATOR),
    DICTIONARIES(ServiceRole.DICTIONARY),
    TTS(ServiceRole.TTS),
    OCR(ServiceRole.OCR),
    SPELL_CHECKERS(ServiceRole.SPELL_CHECKER),
    AI(null),
    OTHER(null)
}

internal object PluginPanelModel {
    fun categories(plugin: PluginState): Set<PluginCategory> {
        // Every role, not one per service: a plugin whose single service both translates and
        // defines words belongs in both categories, and filtering by either should find it.
        val serviceCategories = plugin.services.flatMap { service ->
            service.roles.map { role ->
                when (role) {
                    ServiceRole.TRANSLATOR -> PluginCategory.TRANSLATORS
                    ServiceRole.DICTIONARY -> PluginCategory.DICTIONARIES
                    ServiceRole.TTS -> PluginCategory.TTS
                    ServiceRole.OCR -> PluginCategory.OCR
                    ServiceRole.SPELL_CHECKER -> PluginCategory.SPELL_CHECKERS
                    ServiceRole.SUMMARIZER, ServiceRole.REWRITER -> PluginCategory.AI
                    // Filed under Other rather than given a category of its own, since Wikimedia
                    // is the only source and it already appears under Dictionaries.
                    ServiceRole.IMAGE_SEARCH -> PluginCategory.OTHER
                }
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
