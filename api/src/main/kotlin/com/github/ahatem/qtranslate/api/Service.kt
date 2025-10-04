package com.github.ahatem.qtranslate.api

/**
 * Base interface for all services provided by a plugin.
 */
interface Service {
    /** Unique, machine-readable identifier (e.g., "google-translate"). */
    val id: String

    /** Human-readable name shown in the UI (e.g., "Google Translate"). */
    val name: String

    /** The version of this specific service implementation. */
    val version: String
}