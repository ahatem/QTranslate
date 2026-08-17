package com.github.ahatem.qtranslate.core.plugin.storage

/**
 * Secrets belonging to the application rather than to any plugin.
 *
 * The proxy password is the first of these and the reason this exists. It cannot live in
 * [com.github.ahatem.qtranslate.core.settings.data.Configuration], which is written to disk as
 * plain JSON, and it cannot live in a [ScopedSecretStore], because those are keyed by plugin id
 * and this belongs to no plugin: the proxy is a property of the machine, and every plugin sends
 * through it.
 *
 * Backed by the same [PluginKeyValueStore] as plugin secrets, under a reserved id, so that
 * whenever plugin secrets move to the OS keychain this moves with them rather than being
 * remembered separately. That is the whole reason for reusing the mechanism instead of writing a
 * second one.
 *
 * **Not encrypted yet**, exactly as plugin secrets are not. The separation is what makes
 * encrypting later a change to the storage layer alone; until then the contents sit on disk in the
 * same form the settings do, and the threat model is the file permissions of the data directory.
 */
class AppSecretStore(private val store: PluginKeyValueStore) {

    suspend fun get(key: String): String? = store.getValue(STORE_ID, key)

    suspend fun put(key: String, value: String) {
        // An empty value is a removal. Storing "" would otherwise read back as a password that is
        // set but blank, and a proxy would be sent an empty credential rather than none.
        if (value.isEmpty()) remove(key) else store.storeValue(STORE_ID, key, value)
    }

    suspend fun remove(key: String) = store.deleteValue(STORE_ID, key)

    private companion object {
        /**
         * Reserved, and unusable as a plugin id.
         *
         * Plugin ids are package-shaped, so the `@` cannot collide with one. A bare name like
         * "app" could, and the collision would be silent: two owners writing to one file.
         */
        const val STORE_ID = "@qtranslate.secrets"
    }
}
