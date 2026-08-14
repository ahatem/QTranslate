package com.github.ahatem.qtranslate.core.shared.arch

import com.github.ahatem.qtranslate.api.plugin.ServiceCapability

/**
 * What a service is for, from the host's point of view.
 *
 * The same concept as [ServiceCapability], which is where it is now defined — a service declares
 * its capabilities and the host groups services by them, so there is one source of truth rather
 * than two enums that could drift apart. The alias exists because "type" is the word the host's
 * own code and UI have always used.
 */
typealias ServiceType = ServiceCapability
