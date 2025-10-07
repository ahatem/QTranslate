package com.github.ahatem.qtranslate.api

/**
 * A marker object used as the generic type for Plugins that do not have settings.
 * This provides a type-safe way to declare a plugin's lack of configurable settings.
 *
 * Example: `class MySimplePlugin : Plugin<NoSettings>`
 */
object NoSettings