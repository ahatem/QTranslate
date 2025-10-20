package com.github.ahatem.qtranslate.core.shared.logging

import com.github.ahatem.qtranslate.api.core.Logger

interface LoggerFactory {
    fun getLogger(name: String): Logger
}