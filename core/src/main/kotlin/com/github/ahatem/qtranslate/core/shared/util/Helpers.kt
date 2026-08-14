package com.github.ahatem.qtranslate.core.shared.util

import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.core.shared.arch.ServiceType

/**
 * The capabilities a service declares.
 *
 * This used to test implemented interfaces in a fixed order, which meant a service that both
 * translated and defined words was silently filed under whichever check happened to run first —
 * so it could only ever be offered for one of the two. Services now declare what they can do and
 * are offered under every one of them.
 */
val Service.types: Set<ServiceType> get() = capabilities

/**
 * A single representative capability, for the places that still assume one.
 *
 * Returns `null` when a service declares none, which the host treats as "not selectable".
 */
val Service.type: ServiceType? get() = capabilities.firstOrNull()

/** Whether this service can act as [type]. */
fun Service.hasType(type: ServiceType): Boolean = type in capabilities
