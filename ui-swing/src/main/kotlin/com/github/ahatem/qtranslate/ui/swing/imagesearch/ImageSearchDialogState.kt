package com.github.ahatem.qtranslate.ui.swing.imagesearch

import com.github.ahatem.qtranslate.api.imagesearch.ImageResult
import com.github.ahatem.qtranslate.core.main.domain.model.ServiceInfo
import com.github.ahatem.qtranslate.core.settings.data.Position
import com.github.ahatem.qtranslate.core.settings.data.Size
import com.github.ahatem.qtranslate.core.shared.arch.UiState

/**
 * Everything needed to render [ImageSearchDialog].
 *
 * Callbacks live on the state rather than the constructor so each render closes over current
 * configuration, the same arrangement [com.github.ahatem.qtranslate.ui.swing.dictionary.QuickDictionaryDialogState]
 * uses.
 */
data class ImageSearchDialogState(
    val isVisible: Boolean,
    val isLoading: Boolean,
    val results: List<ImageResult>,
    val searchedTerm: String,
    val hasFailed: Boolean,
    val isPinned: Boolean,
    val availableServices: List<ServiceInfo>,
    val selectedServiceId: String?,
    val config: ImageSearchConfig,
    val strings: ImageSearchStrings,
    val onSearch: (term: String) -> Unit,
    val onServiceSelected: (serviceId: String) -> Unit,
    val onImageOpened: (ImageResult) -> Unit,
    val onPinToggled: () -> Unit,
    val onClose: () -> Unit,
    val onSavePosition: (Position) -> Unit,
    val onSaveSize: (Size) -> Unit
) : UiState

data class ImageSearchConfig(
    val lastKnownSize: Size,
    val lastKnownPosition: Position,
    val positionNearMouse: Boolean = true
)

data class ImageSearchStrings(
    val title: String,
    val hintMessage: String,
    val loadingMessage: String,
    val notFoundMessage: String,
    val errorMessage: String,
    val searchButtonLabel: String,
    val openTooltip: String,
    val openSourceLabel: String,
    val backLabel: String,
    val pinTooltip: String,
    val unpinTooltip: String,
    val closeTooltip: String
)
