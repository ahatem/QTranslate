package com.github.ahatem.qtranslate.ui.swing.main

import com.github.ahatem.qtranslate.core.main.mvi.MainState
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing

/**
 * Delivers main-window snapshots to [start]'s render callback in event order on the Swing
 * dispatcher.
 *
 * The render callback writes the editor's document. Collecting the state on a background
 * dispatcher and hopping to Swing per emission lets a snapshot that predates a keystroke run
 * after the keystroke has already updated the document. [com.github.ahatem.qtranslate.ui.swing.shared.widgets.AdvancedTextPane.render]
 * cannot distinguish that stale echo from a programmatic change, so it rewrites the whole
 * document: a just-deleted character comes back, a just-typed character is dropped, the caret
 * jumps and the undo history is discarded (#413).
 *
 * Collection and rendering belong to the same UI event stream. Starting the collector on Swing
 * means each snapshot is read at an EDT event boundary, after any input event ahead of it has
 * updated the store; a snapshot can therefore never be applied after a newer editor state.
 */
internal class MainStateRenderObserver(
    private val scope: CoroutineScope,
    private val mainState: StateFlow<MainState>,
    private val settingsState: StateFlow<SettingsState>,
    /**
     * Defaults to Swing. Exposed so tests can reproduce the background collection this class
     * exists to prevent; production must leave it at the default.
     */
    private val renderDispatcher: CoroutineDispatcher = Dispatchers.Swing,
) {
    fun start(
        exceptionHandler: CoroutineExceptionHandler,
        render: (MainState, SettingsState) -> Unit,
    ): Job = scope.launch(renderDispatcher + exceptionHandler) {
        mainState.combine(settingsState) { main, settings -> main to settings }
            .distinctUntilChanged()
            .collect { (main, settings) -> render(main, settings) }
    }
}
