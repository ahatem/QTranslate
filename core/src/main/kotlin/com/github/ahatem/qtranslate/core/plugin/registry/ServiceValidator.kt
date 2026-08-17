package com.github.ahatem.qtranslate.core.plugin.registry

import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceRole

/**
 * Checks that a service can actually be offered to the user.
 *
 * ### What this no longer has to check
 * This replaced a validator whose main job was catching a service that declared a role without
 * implementing the matching interface. The host resolved services by the declared value and then
 * cast to the interface, so a mismatch became a `ClassCastException` the first time the user
 * selected the service, and the validator existed to turn that into a load-time failure instead.
 *
 * Roles are now derived from the interfaces themselves, so the two cannot disagree and there is
 * nothing left to catch. What remains are the two ways a service can still be unusable.
 */
internal object ServiceValidator {

    /**
     * Returns a description of every problem found, or an empty list when the service is usable.
     *
     * Reports all of them rather than stopping at the first, so a plugin author fixes them in one
     * pass instead of discovering them one build at a time.
     */
    fun validate(service: Service): List<String> {
        val problems = mutableListOf<String>()

        if (service.key.isBlank()) {
            problems += "service key is blank"
        }

        if (ServiceRole.of(service).isEmpty()) {
            problems += "service '${service.key}' implements no service interface, " +
                    "so it can never be selected"
        }

        return problems
    }
}
