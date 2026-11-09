package com.github.ahatem.qtranslate.ui.swing.main

/**
 * The window that held input focus when an OCR capture started, so dismissing the
 * capture can hand focus back to it — and only to it.
 */
internal enum class CaptureFocusOwner { FRAME, WORKSPACE, OTHER }

/**
 * The window state captured before an OCR capture hides the windows for the
 * screenshot, used to put them back when the capture is dismissed without a
 * captured image.
 */
internal data class PriorCaptureState(
    val frameVisible: Boolean,
    val frameExtendedState: Int,
    val focusOwner: CaptureFocusOwner,
)

/**
 * What restoring [prior] must do. Pure policy, kept separate from the frame so
 * the restore contract is unit-testable without a display.
 *
 * Restore means visibility and extended state go back exactly: a frame that was
 * hidden (tray) before the capture stays hidden, and one that was iconified
 * stays iconified. Raising a window and moving input focus happen only for the
 * window that was focused before the capture — restoring anything else would
 * steal focus from whatever the user is doing.
 */
internal data class CaptureRestorePlan(
    val frameVisible: Boolean,
    val frameExtendedState: Int,
    val raiseFrame: Boolean,
    val raiseWorkspace: Boolean,
    val focusFrameInput: Boolean,
)

internal fun captureRestorePlan(prior: PriorCaptureState): CaptureRestorePlan = CaptureRestorePlan(
    frameVisible = prior.frameVisible,
    frameExtendedState = prior.frameExtendedState,
    raiseFrame = prior.focusOwner == CaptureFocusOwner.FRAME && prior.frameVisible,
    raiseWorkspace = prior.focusOwner == CaptureFocusOwner.WORKSPACE,
    focusFrameInput = prior.focusOwner == CaptureFocusOwner.FRAME && prior.frameVisible,
)
