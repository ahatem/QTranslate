package com.github.ahatem.qtranslate.core.shared.util

import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceRole

/**
 * Every role a service holds, derived from the interfaces it implements.
 *
 * Roles used to be declared alongside those interfaces, which let the two disagree. Before that
 * they were inferred by an ordered type test, which returned one answer and so filed a service
 * that both translated and defined words under whichever branch ran first. Deriving over the whole
 * set is neither: it cannot contradict the code, and it returns all of them.
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
