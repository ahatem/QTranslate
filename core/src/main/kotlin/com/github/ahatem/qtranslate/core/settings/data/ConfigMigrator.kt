package com.github.ahatem.qtranslate.core.settings.data

import com.github.ahatem.qtranslate.api.core.Logger

/**
 * Upgrades persisted [Configuration] objects from older schema versions to the current one.
 *
 * ### How to add a migration
 * 1. Increment [CURRENT_VERSION] in [Configuration] (the `configVersion` field default).
 * 2. Add a `when` branch here that transforms the old shape into the new one and bumps
 *    `configVersion` to the next number.
 * 3. The branch runs automatically on next app launch for any user on the old version.
 *
 * ### Invariant
 * [migrate] is idempotent — running it on an already-current config returns it unchanged.
 */
object ConfigMigrator {

    /** Must match the default value of [Configuration.configVersion]. */
    private const val CURRENT_VERSION = 2

    /**
     * Applies all pending migrations to [config] in order and returns the result.
     * If the config is already at [CURRENT_VERSION] it is returned as-is.
     */
    fun migrate(config: Configuration, logger: Logger): Configuration {
        var current = config
        while (current.configVersion < CURRENT_VERSION) {
            val from = current.configVersion
            current = applyMigration(current, logger)
            // Guard against a buggy migration that forgets to bump the version.
            if (current.configVersion == from) {
                logger.warn("ConfigMigrator: migration from v$from did not advance configVersion — stopping to prevent an infinite loop")
                break
            }
        }
        return current
    }

    // -------------------------------------------------------------------------
    // Private migration steps
    // -------------------------------------------------------------------------

    private fun applyMigration(config: Configuration, logger: Logger): Configuration =
        when (config.configVersion) {
            1 -> {
                // v1 → v2: inject default bindings for any HotkeyActions that were added after the
                // user's config was first saved (FOCUS_INPUT, FOCUS_OUTPUT, FOCUS_EXTRA_OUTPUT).
                // This is safe to run repeatedly — it only adds bindings that are missing.
                logger.info("ConfigMigrator: migrating v1 → v2 — patching missing hotkey defaults")
                val existingActions = config.hotkeys.map { it.action }.toSet()
                val missingDefaults = HotkeyBinding.DEFAULTS.filter { it.action !in existingActions }
                config.copy(
                    configVersion = 2,
                    hotkeys = config.hotkeys + missingDefaults
                )
            }
            else -> {
                logger.warn("ConfigMigrator: unknown configVersion ${config.configVersion} — returning unchanged")
                config
            }
        }
}
