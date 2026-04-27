package com.github.ahatem.qtranslate.ui.swing.update

data class UpdateDialogState(
    val title: String,
    val header: String,
    val details: String,
    val releaseNotes: String,
    val skipButton: String,
    val remindLaterButton: String,
    val downloadButton: String,
    val downloadUrl: String?,
    val onSkip: () -> Unit,
    val onRemindLater: () -> Unit,
)
