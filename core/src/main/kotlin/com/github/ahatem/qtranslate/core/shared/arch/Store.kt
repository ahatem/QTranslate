package com.github.ahatem.qtranslate.core.shared.arch

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** A marker for UI state data classes. */
interface UiState

/** A marker for UI intent sealed classes. */
interface UiIntent

/** A marker for one-shot UI events. */
interface UiEvent

/**
 * A generic interface for an MVI store that manages state and handles one-shot events.
 *
 * @param S The type of the UI state.
 * @param I The type of the UI intent.
 * @param E The type of the UI event.
 */
interface Store<S : UiState, I : UiIntent, E : UiEvent> {
    /** A flow representing the current state of the UI. */
    val state: StateFlow<S>

    /** A flow for one-shot events that should be consumed by the UI once. */
    val events: Flow<E>

    /** The sole entry point for sending user actions or intents to the store. */
    fun dispatch(intent: I)
}