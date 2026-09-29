package com.github.ahatem.qtranslate.ui.swing.imagesearch

import com.github.ahatem.qtranslate.api.imagesearch.ImageResult
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.main.mvi.MainState
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.Position
import com.github.ahatem.qtranslate.core.settings.data.Size

internal fun buildImageSearchDialogState(
    mainState: MainState,
    config: Configuration,
    localizer: LocalizationManager,
    onSearch: (String) -> Unit,
    onServiceSelected: (String) -> Unit,
    onImageOpened: (ImageResult) -> Unit,
    onPinToggled: () -> Unit,
    onClose: () -> Unit,
    onSavePosition: (Position) -> Unit,
    onSaveSize: (Size) -> Unit
): ImageSearchDialogState {
    val serviceType = ServiceRole.IMAGE_SEARCH
    val available = mainState.getAvailableServicesFor(serviceType)
    val selectedId = config.getActivePreset()?.selectedServices?.get(serviceType)

    return ImageSearchDialogState(
        isVisible         = mainState.isImageSearchVisible,
        isLoading         = mainState.isImageSearchLoading,
        results           = mainState.imageResults,
        searchedTerm      = mainState.imageSearchTerm,
        hasFailed         = mainState.imageSearchFailed,
        isPinned          = mainState.isImageSearchPinned,
        triggerCount      = mainState.imageSearchTriggerCount,
        availableServices = available,
        selectedServiceId = selectedId,
        config = ImageSearchConfig(
            lastKnownSize     = config.imageSearchLastKnownSize,
            lastKnownPosition = config.imageSearchLastKnownPosition,
            positionNearMouse = config.isImageSearchAutoPositionEnabled,
            closeOnClickOutside = config.closePopupsOnClickOutside,
            transparencyPercentage = config.imageSearchTransparencyPercentage
        ),
        strings = imageSearchStrings(localizer, mainState.imageSearchTerm),
        onSearch = onSearch,
        onServiceSelected = onServiceSelected,
        onImageOpened = onImageOpened,
        onPinToggled = onPinToggled,
        onClose = onClose,
        onSavePosition = onSavePosition,
        onSaveSize = onSaveSize
    )
}
