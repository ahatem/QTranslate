package com.github.ahatem.qtranslate.app

import kotlinx.serialization.json.Json

/** The JSON settings shared by the application's HTTP client and its persisted stores. */
internal val appJson = Json { ignoreUnknownKeys = true; isLenient = true }
