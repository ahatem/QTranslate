package com.github.ahatem.qtranslate.api.plugin

/**
 * Typed persistence for a plugin.
 *
 * Everything used to be a `String`, which meant every plugin hand-rolled the same parsing and
 * silently misbehaved when a stored value was malformed. These accessors do that once, in one
 * place, and fall back to the supplied default rather than throwing.
 *
 * ### Scope
 * Values are scoped to the plugin **and the instance the user created**, so two instances of the
 * same plugin never see each other's data. Plugins do not participate in this: keys are plain
 * strings and the host qualifies them.
 *
 * A [serviceKey] may be supplied to scope a value to one service within the plugin. That is what
 * allows a single plugin to use, say, a cheap model for translation and a stronger one for
 * summarizing — previously impossible, because one settings blob covered every service the
 * plugin offered. Reads fall back to the plugin-wide value when no service-specific one exists,
 * so plugins that do not need this can ignore it entirely.
 */
interface SettingsStore {

    suspend fun getString(key: String, default: String? = null, serviceKey: String? = null): String?
    suspend fun getInt(key: String, default: Int, serviceKey: String? = null): Int
    suspend fun getLong(key: String, default: Long, serviceKey: String? = null): Long
    suspend fun getDouble(key: String, default: Double, serviceKey: String? = null): Double
    suspend fun getBoolean(key: String, default: Boolean, serviceKey: String? = null): Boolean

    suspend fun put(key: String, value: String, serviceKey: String? = null)
    suspend fun put(key: String, value: Int, serviceKey: String? = null)
    suspend fun put(key: String, value: Long, serviceKey: String? = null)
    suspend fun put(key: String, value: Double, serviceKey: String? = null)
    suspend fun put(key: String, value: Boolean, serviceKey: String? = null)

    /** Removes a value. No-op when absent. */
    suspend fun remove(key: String, serviceKey: String? = null)
}

/**
 * Storage for credentials — API keys, tokens, anything that would be damaging to leak.
 *
 * Kept apart from [SettingsStore] for two reasons. It lets the host put secrets somewhere safer
 * than ordinary settings, using the operating system keychain where one is available, without
 * every plugin having to know or care. And it makes the sensitive values obvious in plugin code,
 * so they are not accidentally logged, exported, or included in a diagnostics bundle.
 *
 * Where no platform keychain is available the host falls back to its own storage; a plugin
 * should assume a secret is protected as well as the platform allows, and no better.
 */
interface SecretStore {

    /** Returns the stored secret, or `null` when unset. */
    suspend fun get(key: String): String?

    suspend fun put(key: String, value: String)

    /** Removes a secret. No-op when absent. */
    suspend fun remove(key: String)

    /**
     * Whether a secret is present, without reading it.
     *
     * Lets a plugin report [ServiceError.ConfigurationError] for "no key set" without pulling the
     * value into memory only to discard it.
     */
    suspend fun contains(key: String): Boolean
}
