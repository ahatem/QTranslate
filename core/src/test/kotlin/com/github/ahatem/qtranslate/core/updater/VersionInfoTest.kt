package com.github.ahatem.qtranslate.core.updater

import com.github.ahatem.qtranslate.core.updater.data.VersionInfo
import kotlin.test.Test
import kotlin.test.assertEquals

class VersionInfoTest {
    private fun newer(remote: String, current: String) =
        VersionInfo(remote, "", "", null).isNewerThan(current)

    @Test
    fun `semantic version precedence and build metadata`() {
        listOf(
            "1.4.1-rc.2" to "1.4.1-rc.1",
            "1.4.1-rc.1" to "1.4.1-beta.1",
            "1.4.1-beta.1" to "1.4.1-alpha.1",
            "1.4.1-rc.1" to "1.4.0",
            "1.4.2" to "1.4.1",
            "1.4.1" to "1.4.1-rc.1"
        ).forEach { (later, earlier) ->
            assertEquals(true, newer(later, earlier), "$later > $earlier")
            assertEquals(false, newer(earlier, later), "$earlier <= $later")
        }
        assertEquals(false, newer("1.4.1", "1.4.1"))
        assertEquals(false, newer("v1.4.1", "1.4.1"))
        assertEquals(false, newer("1.4.1+build.9", "1.4.1+build.1"))
        assertEquals(false, newer("1.4.1-rc.01", "1.4.0"))
        assertEquals(false, newer("invalid", "1.4.0"))
        assertEquals(false, newer("1.4.1", "invalid"))
    }
}
