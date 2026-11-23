package com.github.ahatem.qtranslate.plugins.common

import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.fold

/** The success value, failing the test with the provider error message when the call failed. */
fun <T> Result<T, ServiceError>.unwrap(): T =
    fold(success = { it }, failure = { throw AssertionError("Expected success, but was ${it.message}") })

/** The error, failing the test when the call unexpectedly succeeded. */
fun Result<*, ServiceError>.unwrapError(): ServiceError =
    fold(success = { throw AssertionError("Expected a failure, but got $it") }, failure = { it })
