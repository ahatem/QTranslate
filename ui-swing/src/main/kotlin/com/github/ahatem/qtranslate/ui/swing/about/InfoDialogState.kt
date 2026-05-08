package com.github.ahatem.qtranslate.ui.swing.about

import javax.swing.Icon

data class InfoDialogState(
    val title: String,
    val appName: String,
    val versionText: String,
    val descriptionHtml: String,
    val websiteUrl: String,
    val icon: Icon,
    val closeButtonText: String,
    val isVisible: Boolean = false,
    /** URL opened when the user clicks the support button. Empty string = hide the button. */
    val supportUrl: String = "",
    /** Label for the "Support this project" button. */
    val supportButtonText: String = ""
)
