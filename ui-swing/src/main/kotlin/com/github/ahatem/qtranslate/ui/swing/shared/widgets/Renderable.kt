package com.github.ahatem.qtranslate.ui.swing.shared.widgets

import com.github.ahatem.qtranslate.core.shared.arch.UiState

/**
 * An interface for a "dump" UI component that follows the MVI pattern.
 *
 * @param S The type of the State object this component can render.
 */
interface Renderable<S : UiState> {
    /**
     * The single entry point for updating the component's view.
     * This method should be completely self-contained and rely only on the
     * provided state to draw the entire component.
     *
     * @param state The state object to be rendered.
     */
    fun render(state: S)
}