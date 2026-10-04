package com.github.ahatem.qtranslate.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A fresh startup must not pay to register a bundled font nobody asked for.
 *
 * A runtime assertion against `GraphicsEnvironment` cannot protect this reliably: font registration
 * is process-wide, Gradle runs a module's tests in one shared JVM, and another test in this suite
 * may have already registered the same family by the time this one runs. The source itself is the
 * stable boundary — [AppUiSetup.installFonts] registers every bundled face for lazy loading and
 * nothing more, so the actual font files are read only the first time something asks for that
 * family by name (FlatLaf resolving the interface font, or `FontConfig.toFont` resolving a saved
 * editor or fallback font) — never merely because startup ran.
 */
class AppUiSetupFontRegistrationTest {

    private fun installFontsBody(): String {
        val source = File("src/main/kotlin/com/github/ahatem/qtranslate/app/AppUiSetup.kt").readText()
        val start = source.indexOf("private fun installFonts()")
        require(start >= 0) { "AppUiSetup.installFonts() was not found — has it been renamed?" }
        val bodyStart = source.indexOf('{', start)
        var depth = 0
        var end = bodyStart
        while (end < source.length) {
            when (source[end]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return source.substring(bodyStart, end + 1) }
            }
            end++
        }
        error("Unbalanced braces while scanning installFonts()")
    }

    @Test
    fun `startup registers the bundled Arabic face lazily, not eagerly`() {
        val body = installFontsBody()
        assertTrue("NotoNaskhArabicFont.installLazy()" in body, "expected a lazy registration call:\n$body")
        assertFalse("NotoNaskhArabicFont.install()" in body, "an eager install() call would read the font files on every startup:\n$body")
    }

    @Test
    fun `startup registers Inter and Rubik lazily too`() {
        val body = installFontsBody()
        assertTrue("FlatInterFont.installLazy()" in body, "expected Inter to be registered lazily:\n$body")
        assertTrue("RubikSansFont.installLazy()" in body, "expected Rubik to be registered lazily:\n$body")
    }

    @Test
    fun `startup registers the portable rescue faces lazily, not eagerly`() {
        val body = installFontsBody()
        assertTrue("PortableFallbackFonts.installLazy()" in body, "expected a lazy registration call:\n$body")
        assertFalse("PortableFallbackFonts.install(" in body, "an eager install would read the font files on every startup:\n$body")
        assertFalse("installAll()" in body, "installing the whole chain on startup would do the same:\n$body")
    }
}
