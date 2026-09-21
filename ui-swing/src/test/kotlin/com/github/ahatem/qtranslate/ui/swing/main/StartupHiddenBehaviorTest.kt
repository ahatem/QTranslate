package com.github.ahatem.qtranslate.ui.swing.main

import java.io.DataInputStream
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Hidden startup behavior for the final part of #226.
 *
 * A Windows login launch carries `--startup` and must construct the frame exactly as usual
 * (tray, hotkeys, services) but never show it — while every restore path (tray, global
 * SHOW_MAIN_WINDOW hotkey, second-instance activation) reuses the canonical [showAndFocus].
 *
 * Real windows need a display, so like [MainWindowFocusOnShowTest] these stay headless-safe:
 * method-scoped bytecode assertions plus a constructor contract for the hidden-startup flag.
 * No Robot, no timing.
 */
class StartupHiddenBehaviorTest {

    // -----------------------------------------------------------------------
    // Construction contract
    // -----------------------------------------------------------------------

    @Test
    fun `frame construction accepts a hidden-startup flag defaulting to visible`() {
        val field = runCatching { MainAppFrame::class.java.getDeclaredField("initiallyHidden") }
            .getOrNull()
        assertTrue(field != null, "MainAppFrame must retain the initiallyHidden construction flag")
        assertTrue(
            field.type == Boolean::class.javaPrimitiveType,
            "initiallyHidden must be a boolean flag",
        )

        // The primary constructor takes the flag last; the synthetic default-arguments
        // overload ends with the Kotlin DefaultConstructorMarker instead.
        val primary = MainAppFrame::class.java.declaredConstructors
            .filter { ctor ->
                ctor.parameterTypes.none { it.name.endsWith("DefaultConstructorMarker") }
            }
        assertTrue(primary.isNotEmpty(), "MainAppFrame must have a callable primary constructor")
        assertTrue(
            primary.any { it.parameterTypes.lastOrNull() == Boolean::class.javaPrimitiveType },
            "MainAppFrame primary constructor must end with the initiallyHidden flag",
        )
    }

    // -----------------------------------------------------------------------
    // Restore contract: every path reuses the canonical showAndFocus
    // -----------------------------------------------------------------------

    @Test
    fun `canonical restore makes the frame visible instead of minimized`() {
        val calls = invokedMethods(MainAppFrame::class.java, "showAndFocus")
        assertTrue(
            "setVisible" in calls,
            "showAndFocus must make the frame visible (isVisible = true); found: $calls",
        )
        assertTrue(
            "setState" in calls,
            "showAndFocus must clear any minimized state (state = NORMAL); found: $calls",
        )
    }

    @Test
    fun `canonical restore focuses the input editor`() {
        val calls = invokedMethods(MainAppFrame::class.java, "showAndFocus")
        assertTrue(
            "requestFocusOnInput" in calls,
            "showAndFocus must land focus in the input editor (#267); found: $calls",
        )
    }

    @Test
    fun `tray left-click restores through the canonical path`() {
        // mouseClicked only schedules runOnUi { showAndFocus() }; the call itself lives in the
        // lambda implementation method, so the whole listener class is in scope.
        val calls = invokedMethodsInClass(trayMouseListenerClass())
        assertTrue(
            "showAndFocus" in calls,
            "tray left-click must restore via showAndFocus; found: $calls",
        )
    }

    // -----------------------------------------------------------------------
    // Hidden startup must not gate hotkeys on first show
    // -----------------------------------------------------------------------

    @Test
    fun `global hotkeys initialize eagerly instead of waiting for first show`() {
        val calls = invokedMethods(MainAppFrame::class.java, "setupGlobalHotkeys")
        assertTrue(
            "initializeGlobalHotkeys" in calls,
            "setupGlobalHotkeys must initialize hotkeys eagerly — windowOpened never fires " +
                "while the frame starts hidden; found: $calls",
        )
    }

    @Test
    fun `eager hotkey path brings up the backend and local bindings`() {
        val calls = invokedMethods(MainAppFrame::class.java, "initializeGlobalHotkeys")
        assertTrue(
            "initialize" in calls,
            "initializeGlobalHotkeys must start the global input backend; found: $calls",
        )
        assertTrue(
            "registerLocalHotkeys" in calls,
            "initializeGlobalHotkeys must install local bindings; found: $calls",
        )
    }

    @Test
    fun `first show re-applies hotkey state through the same path`() {
        val listener = hotkeyWindowListenerClass()
        val calls = invokedMethods(listener, "windowOpened")
        // The listener is an inner class calling a private member, so the bytecode shows the
        // synthetic accessor (access$initializeGlobalHotkeys).
        assertTrue(
            calls.any { it.contains("initializeGlobalHotkeys") },
            "windowOpened must re-apply hotkeys through the same idempotent path; found: $calls",
        )
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Anonymous listeners are compiled to `MainAppFrame$<method>$N` classes. The tray mouse
     * listener sits one object-expression deeper (inside the TrayIcon apply block), so both
     * nesting shapes are probed.
     */
    private fun trayMouseListenerClass(): Class<*> {
        val loader = MainAppFrame::class.java.classLoader
        val base = "${MainAppFrame::class.java.name}\$setupTrayMenu\$"
        for (n in 1..8) {
            val direct = runCatching { Class.forName("$base$n", false, loader) }.getOrNull()
            if (direct != null && direct.declaredMethods.any { it.name == "mouseClicked" }) return direct
            for (m in 1..8) {
                val nested = runCatching { Class.forName("$base$n\$$m", false, loader) }.getOrNull()
                if (nested != null && nested.declaredMethods.any { it.name == "mouseClicked" }) return nested
            }
        }
        fail("tray mouse listener with mouseClicked not found on MainAppFrame")
    }

    private fun hotkeyWindowListenerClass(): Class<*> {
        val loader = MainAppFrame::class.java.classLoader
        val prefix = "${MainAppFrame::class.java.name}\$setupGlobalHotkeys\$"
        for (n in 1..32) {
            val candidate = runCatching { Class.forName(prefix + n, false, loader) }.getOrNull()
                ?: continue
            if (candidate.declaredMethods.any { it.name == "windowOpened" }) return candidate
        }
        fail("hotkey window listener for show events not found on MainAppFrame")
    }

    /** Method names invoked directly by the named method's own bytecode. */
    private fun invokedMethods(owner: Class<*>, methodName: String): Set<String> =
        scanClass(owner, methodName)
            ?: fail("method $methodName not found in ${owner.name}")

    /** Method names invoked by any method of the class (covers lambda implementations). */
    private fun invokedMethodsInClass(owner: Class<*>): Set<String> =
        scanClass(owner, null) ?: fail("no methods found in ${owner.name}")

    /**
     * Invoked method names for one method — or every method when [methodName] is null.
     * Returns null when a named method is absent.
     */
    private fun scanClass(owner: Class<*>, methodName: String?): Set<String>? {
        val bytes = owner.classLoader
            .getResourceAsStream(owner.name.replace('.', '/') + ".class")
            ?.readBytes()
            ?: fail("cannot load bytecode for ${owner.name}")
        val stream = DataInputStream(bytes.inputStream())
        stream.skipBytes(8)
        val cpCount = stream.readUnsignedShort()
        val utf8 = arrayOfNulls<String>(cpCount)
        // Method/field refs point at a NameAndType entry; NameAndType points at the name.
        val refNat = IntArray(cpCount)
        val natName = IntArray(cpCount)
        var i = 1
        while (i < cpCount) {
            when (stream.readUnsignedByte()) {
                1 -> {
                    val len = stream.readUnsignedShort()
                    val raw = ByteArray(len)
                    stream.readFully(raw)
                    utf8[i] = String(raw, Charsets.UTF_8)
                }
                3, 4 -> stream.skipBytes(4)
                5, 6 -> {
                    stream.skipBytes(8)
                    i++
                }
                7, 8, 16, 19, 20 -> stream.skipBytes(2)
                9, 10, 11 -> {
                    stream.skipBytes(2)
                    refNat[i] = stream.readUnsignedShort()
                }
                12 -> {
                    natName[i] = stream.readUnsignedShort()
                    stream.skipBytes(2)
                }
                17, 18 -> stream.skipBytes(4)
                15 -> stream.skipBytes(3)
                else -> fail("unsupported constant-pool tag in ${owner.name}")
            }
            i++
        }
        fun methodNameOf(ref: Int): String? {
            if (ref <= 0 || ref >= cpCount) return null
            val nat = refNat[ref]
            if (nat <= 0 || nat >= cpCount) return null
            val nameIndex = natName[nat]
            if (nameIndex <= 0) return null
            return utf8[nameIndex]
        }
        stream.skipBytes(6)
        val interfaceCount = stream.readUnsignedShort()
        stream.skipBytes(interfaceCount * 2)
        val fieldCount = stream.readUnsignedShort()
        repeat(fieldCount) {
            stream.skipBytes(6)
            val attrs = stream.readUnsignedShort()
            repeat(attrs) {
                stream.skipBytes(2)
                stream.skipBytes(stream.readInt())
            }
        }
        val methodCount = stream.readUnsignedShort()
        val found = mutableSetOf<String>()
        var matched = 0
        repeat(methodCount) {
            stream.skipBytes(2)
            val name = utf8[stream.readUnsignedShort()]
            stream.skipBytes(2)
            val attrs = stream.readUnsignedShort()
            repeat(attrs) {
                val attrName = utf8[stream.readUnsignedShort()]
                val len = stream.readInt()
                if ((methodName == null || name == methodName) && attrName == "Code") {
                    matched++
                    stream.skipBytes(4)
                    val codeLen = stream.readInt()
                    val code = ByteArray(codeLen)
                    stream.readFully(code)
                    found += scanInvokes(code, ::methodNameOf)
                    stream.skipBytes(len - (8 + codeLen))
                } else {
                    stream.skipBytes(len)
                }
            }
        }
        if (methodName != null && matched == 0) return null
        return found
    }

    private fun scanInvokes(code: ByteArray, resolve: (Int) -> String?): Set<String> {
        val calls = mutableSetOf<String>()
        var pos = 0
        while (pos < code.size) {
            val op = code[pos].toInt() and 0xFF
            if (op == 0xB6 || op == 0xB7 || op == 0xB8 || op == 0xB9) {
                val index = ((code[pos + 1].toInt() and 0xFF) shl 8) or (code[pos + 2].toInt() and 0xFF)
                resolve(index)?.let(calls::add)
            }
            pos += instructionLength(code, pos).coerceAtLeast(1)
        }
        return calls
    }

    private fun instructionLength(code: ByteArray, pos: Int): Int {
        return when (code[pos].toInt() and 0xFF) {
            0x10, 0x12, 0x15, 0x16, 0x17, 0x18, 0x19,
            0x36, 0x37, 0x38, 0x39, 0x3A, 0xA9, 0xBC -> 2
            0x11, 0x13, 0x14, 0xB2, 0xB3, 0xB4, 0xB5,
            0xB6, 0xB7, 0xB8, 0xBB, 0xBD, 0xC0, 0xC1,
            0x99, 0x9A, 0x9B, 0x9C, 0x9D, 0x9E, 0x9F,
            0xA0, 0xA1, 0xA2, 0xA3, 0xA4, 0xA5, 0xA6,
            0xA7, 0xA8 -> 3
            0xC5 -> 4
            0xB9, 0xBA, 0xC8, 0xC9 -> 5
            0xC4 -> if ((code[pos + 1].toInt() and 0xFF) == 0x84) 6 else 4
            0xAA -> {
                val base = pos + 1 + ((4 - ((pos + 1) % 4)) % 4)
                val low = readInt(code, base + 4)
                val high = readInt(code, base + 8)
                1 + ((4 - ((pos + 1) % 4)) % 4) + 12 + (high - low + 1) * 4
            }
            0xAB -> {
                val base = pos + 1 + ((4 - ((pos + 1) % 4)) % 4)
                val npairs = readInt(code, base + 4)
                1 + ((4 - ((pos + 1) % 4)) % 4) + 8 + npairs * 8
            }
            else -> 1
        }
    }

    private fun readInt(code: ByteArray, pos: Int): Int =
        ((code[pos].toInt() and 0xFF) shl 24) or
            ((code[pos + 1].toInt() and 0xFF) shl 16) or
            ((code[pos + 2].toInt() and 0xFF) shl 8) or
            (code[pos + 3].toInt() and 0xFF)
}
