package com.github.ahatem.qtranslate.core.main.domain.usecase

import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.api.spellchecker.Correction
import com.github.ahatem.qtranslate.api.spellchecker.SpellCheckRequest
import com.github.ahatem.qtranslate.api.spellchecker.SpellChecker
import com.github.ahatem.qtranslate.core.main.mvi.MainState
import com.github.ahatem.qtranslate.core.settings.data.ActiveServiceManager
import com.github.ahatem.qtranslate.core.shared.arch.ServiceType
import com.github.michaelbull.result.getOr
import com.github.michaelbull.result.map
import com.github.michaelbull.result.onFailure

class PerformSpellCheckUseCase(
    private val activeServiceManager: ActiveServiceManager
) {
    suspend operator fun invoke(
        currentState: MainState,
        text: String,
        onStatusUpdate: suspend (message: String, type: NotificationType) -> Unit
    ): List<Correction> {
        val spellChecker = activeServiceManager.getActiveService<SpellChecker>(ServiceType.SPELL_CHECKER, currentState)
            ?: return emptyList()

        return spellChecker.check(SpellCheckRequest(text))
            .onFailure { error ->
                onStatusUpdate("Spell check failed: ${error.message}", NotificationType.ERROR)
            }
            .map { it.corrections }
            .getOr(emptyList())
    }
}