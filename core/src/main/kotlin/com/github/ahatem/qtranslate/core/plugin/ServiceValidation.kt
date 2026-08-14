package com.github.ahatem.qtranslate.core.plugin

import com.github.ahatem.qtranslate.api.plugin.ServiceError

/**
 * What one service said when asked whether it is correctly configured.
 *
 * The point of asking is to find out *before* a translation fails: a wrong API key otherwise
 * surfaces the first time the user selects some text and gets an error instead of a translation,
 * with no obvious connection to the key they typed a week earlier.
 *
 * @property error `null` when the service is ready. Otherwise the reason, which distinguishes
 *   "not set up yet" ([ServiceError.ConfigurationError]) from "set up, but the credentials were
 *   refused" ([ServiceError.AuthenticationError]) — different problems needing different fixes.
 */
data class ServiceValidation(
    val serviceId: String,
    val serviceName: String,
    val error: ServiceError?
) {
    val isReady: Boolean get() = error == null
}
