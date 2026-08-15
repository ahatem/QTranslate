package com.github.ahatem.qtranslate.core.plugin.registry

/**
 * Builds and parses the identifiers the host assigns to services.
 *
 * A plugin declares only a [com.github.ahatem.qtranslate.api.plugin.Service.key] that is unique
 * within itself. The host qualifies that with the plugin and the instance, producing the string
 * it persists in presets and disabled-service lists.
 *
 * Keeping the composition here rather than asking plugins for a globally unique id is what lets
 * one plugin be installed several times with different credentials: both instances declare the
 * same service key and remain distinguishable.
 *
 * Format: `pluginId:instanceId:serviceKey` — for example `ai-services:default:translator`.
 */
object ServiceId {

    private const val SEPARATOR = ':'

    /**
     * The instance every plugin has before the user creates more.
     *
     * Present from the outset so identifiers never change shape when multi-instance support
     * arrives; only the value varies.
     */
    const val DEFAULT_INSTANCE = "default"

    fun of(pluginId: String, instanceId: String, serviceKey: String): String =
        "$pluginId$SEPARATOR$instanceId$SEPARATOR$serviceKey"

    /** Splits an identifier, or returns `null` when it is not in the expected format. */
    fun parse(serviceId: String): Parts? {
        val parts = serviceId.split(SEPARATOR)
        if (parts.size != 3 || parts.any(String::isBlank)) return null
        return Parts(parts[0], parts[1], parts[2])
    }

    /** The plugin an identifier belongs to, or `null` when it cannot be parsed. */
    fun pluginIdOf(serviceId: String): String? = parse(serviceId)?.pluginId

    data class Parts(val pluginId: String, val instanceId: String, val serviceKey: String)
}
