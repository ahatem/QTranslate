package com.github.ahatem.qtranslate.ui.swing.main

import kotlin.test.Test
import kotlin.test.assertEquals

class LoadingIndicatorVisibilityTest {

    @Test
    fun `the marker covers waits that have nowhere else to show progress`() {
        data class Case(val loading: Boolean, val popupPending: Boolean, val replacing: Boolean, val shown: Boolean)
        listOf(
            // An inline replace never raises the form's loading flag and has no window.
            Case(loading = false, popupPending = false, replacing = true, shown = true),
            Case(loading = true, popupPending = true, replacing = false, shown = true),
            Case(loading = false, popupPending = false, replacing = false, shown = false),
            // The main window reports its own progress.
            Case(loading = true, popupPending = false, replacing = false, shown = false),
        ).forEach {
            assertEquals(
                it.shown,
                showsLoadingIndicator(isLoading = it.loading, popupPending = it.popupPending, isReplacing = it.replacing),
                it.toString(),
            )
        }
    }
}
