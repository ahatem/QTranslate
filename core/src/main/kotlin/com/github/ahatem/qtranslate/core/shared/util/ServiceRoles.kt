package com.github.ahatem.qtranslate.core.shared.util

import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceRole

/**
 * Every role a service holds, derived from the interfaces it implements.
 *
 * Deriving over the whole set cannot contradict the code, and a service that both translates and
 * defines words holds both roles.
 */
val Service.roles: Set<ServiceRole> get() = ServiceRole.of(this)

/**
 * A single representative role, for the places that still assume one.
 *
 * Returns `null` when a service implements no role interface, which the host treats as
 * "not selectable".
 */
val Service.role: ServiceRole? get() = roles.firstOrNull()

/** Whether this service can act as [role]. */
fun Service.hasRole(role: ServiceRole): Boolean = role.isHeldBy(this)
