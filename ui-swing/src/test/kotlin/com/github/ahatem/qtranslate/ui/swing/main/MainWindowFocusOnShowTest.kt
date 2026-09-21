package com.github.ahatem.qtranslate.ui.swing.main

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.localization.LanguageTomlParser
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.data.FontConfig
import com.github.ahatem.qtranslate.ui.swing.main.input.InputTextPanel
import com.github.ahatem.qtranslate.ui.swing.main.input.InputTextState
import com.github.ahatem.qtranslate.ui.swing.main.widgets.TextActionsState
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import java.io.DataInputStream
import javax.swing.JComponent
import javax.swing.JTextPane
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Final half of #216: when the main window is shown or re-shown for normal
 * user interaction, keyboard focus must land in the input editor.
 *
 * The bug this pins: [MainContentView.requestFocusOnInput] historically called
 * `requestFocusInWindow()` on the input *panel* instead of the editor. A
 * JPanel accepts focus, so the request did not fail — it landed on the
 * container, where typing goes nowhere. The seam must target the editor via
 * `requestFocusOnText()`.
 *
 * Real focus transfer needs a display, so these tests stay headless-safe:
 * real components for the focusability contract, plus method-scoped bytecode
 * assertions proving which seam each show/update/dialog path invokes. No
 * Robot, no timing.
 */
class MainWindowFocusOnShowTest {

    private fun <T> onEdt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var result: T? = null
        SwingUtilities.invokeAndWait { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private class SilentLogger : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    private val testLocalizer by lazy {
        val dir = java.nio.file.Files.createTempDirectory("qtranslate-focus-test").toFile()
        LocalizationManager(dir, LanguageTomlParser(), SilentLogger())
    }

    /**
     * IconManager's only constructor needs a full PluginManager graph, but
     * panel construction never calls into it — icons resolve lazily at render
     * from [com.github.ahatem.qtranslate.ui.swing.main.widgets.TextActionsPanel].
     * Allocate without running the constructor; any future call into it would
     * fail loudly here instead of silently changing test meaning.
     */
    private fun blindIconManager(): IconManager {
        val theUnsafe = Class.forName("sun.misc.Unsafe")
            .getDeclaredField("theUnsafe")
            .apply { isAccessible = true }
            .get(null)
        val allocate = theUnsafe.javaClass.getMethod("allocateInstance", Class::class.java)
        return allocate.invoke(theUnsafe, IconManager::class.java) as IconManager
    }

    private fun newInputPanel(): InputTextPanel = onEdt {
        InputTextPanel(
            iconManager = blindIconManager(),
            localizationManager = testLocalizer,
            onTextChanged = {},
            onListen = {},
            onTranslateRequest = {},
            onCorrectionApplied = { _, _ -> },
        )
    }

    private fun renderText(panel: InputTextPanel, text: String) {
        val font = FontConfig("SansSerif", 14)
        panel.render(
            InputTextState(
                text = text,
                corrections = emptyList(),
                fontConfig = font,
                fallbackFontConfig = font,
                isEditable = true,
                isLoading = false,
                actionsState = TextActionsState(emptyList()),
            )
        )
    }

    // -----------------------------------------------------------------------
    // Focus-seam contract on real components
    // -----------------------------------------------------------------------

    @Test
    fun `input editor is the only viable focus target inside its panel`() {
        val panel = newInputPanel()

        assertTrue(
            panel.textPaneComponent.isFocusable,
            "the input editor must accept focus — it is the only valid focus target",
        )
        assertTrue(
            panel.textPaneComponent.isRequestFocusEnabled,
            "the input editor must have focus requests enabled",
        )
        assertFalse(
            panel.textPaneComponent === (panel as JComponent),
            "the focus target must be the inner editor, never the outer container: " +
                "focus landing on the container leaves typing going nowhere",
        )
    }

    @Test
    fun `input focus seam preserves existing text across repeated shows`() {
        val panel = newInputPanel()
        onEdt {
            renderText(panel, "hello world")
            panel.requestFocusOnText()
            panel.requestFocusOnText()
        }

        assertEquals(
            "hello world",
            onEdt { (panel.textPaneComponent as JTextPane).text },
            "requesting input focus must not clear or mutate the editor text",
        )
    }

    // -----------------------------------------------------------------------
    // Show/update/dialog wiring via method-scoped bytecode
    // -----------------------------------------------------------------------

    @Test
    fun `requestFocusOnInput delegates to the input text pane`() {
        assertInvokes(
            owner = MainContentView::class.java,
            method = "requestFocusOnInput",
            targetOwnerSuffix = "ui/swing/main/input/InputTextPanel",
            targetMethod = "requestFocusOnText",
        )
    }

    @Test
    fun `canonical show requests input focus`() {
        assertInvokes(
            owner = MainAppFrame::class.java,
            method = "showAndFocus",
            targetOwnerSuffix = "ui/swing/main/MainContentView",
            targetMethod = "requestFocusOnInput",
        )
    }

    @Test
    fun `window show and restore events route to input focus`() {
        val listener = windowListenerClass()
        assertInvokes(
            owner = listener,
            method = "windowOpened",
            targetOwnerSuffix = "ui/swing/main/MainContentView",
            targetMethod = "requestFocusOnInput",
        )
        assertInvokes(
            owner = listener,
            method = "windowDeiconified",
            targetOwnerSuffix = "ui/swing/main/MainContentView",
            targetMethod = "requestFocusOnInput",
        )
    }

    /**
     * The show/restore listener is an anonymous `WindowAdapter` inside
     * `setupWindowListeners`, which `getDeclaredClasses` does not report.
     * Probe the conventional `setupWindowListeners$N` names instead.
     */
    private fun windowListenerClass(): Class<*> {
        val loader = MainAppFrame::class.java.classLoader
        val prefix = "${MainAppFrame::class.java.name}\$setupWindowListeners\$"
        for (n in 1..32) {
            val candidate = runCatching { Class.forName(prefix + n, false, loader) }.getOrNull()
                ?: continue
            if (candidate.declaredMethods.any { it.name == "windowDeiconified" }) return candidate
        }
        fail("window listener for show/restore events not found on MainAppFrame")
    }

    @Test
    fun `generic render never requests focus`() {
        listOf("render", "renderComponents", "renderDictionaryPanel").forEach { method ->
            assertNeverInvokesFocusSeam(
                owner = MainContentView::class.java,
                method = method,
            )
        }
    }

    @Test
    fun `child dialogs keep their own focus`() {
        listOf(
            "openSettingsDialog",
            "onShowAboutDialog",
            "showImageSearchDialog",
            "showDictionaryDialog",
            "showHistoryDialog",
        ).forEach { method ->
            assertNeverInvokesFocusSeam(
                owner = MainAppFrame::class.java,
                method = method,
            )
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Main-window focus seams that would pull focus back to the input editor. */
    private val focusSeams = setOf(
        "requestFocusOnInput",
        "requestFocusOnText",
        "switchToAndFocusInput",
    )

    private fun assertInvokes(owner: Class<*>, method: String, targetOwnerSuffix: String, targetMethod: String) {
        val calls = invokedMethods(owner, method)
        assertTrue(
            calls.any { (callOwner, callMethod) ->
                callMethod == targetMethod && callOwner.endsWith(targetOwnerSuffix)
            },
            "${owner.simpleName}.$method must invoke $targetOwnerSuffix.$targetMethod; " +
                "found: ${calls.take(10)}",
        )
    }

    private fun assertNeverInvokesFocusSeam(owner: Class<*>, method: String) {
        val calls = invokedMethods(owner, method)
        val offenders = calls.filter { (_, callMethod) -> callMethod in focusSeams }
        assertTrue(
            offenders.isEmpty(),
            "${owner.simpleName}.$method must not pull focus to the input editor; found: $offenders",
        )
    }

    private fun classBytes(cls: Class<*>): ByteArray {
        val path = cls.name.replace('.', '/') + ".class"
        return cls.classLoader.getResourceAsStream(path)?.readBytes()
            ?: fail("cannot load bytecode for ${cls.name}")
    }

    /**
     * All (owner, method) pairs invoked directly by the named method's own
     * code — lambdas and callees are out of scope by construction, so an
     * update path that merely *holds* a focus callback still passes.
     */
    private fun invokedMethods(owner: Class<*>, methodName: String): Set<Pair<String, String>> {
        val bytes = classBytes(owner)
        val stream = DataInputStream(bytes.inputStream())
        stream.skipBytes(8)
        val cpCount = stream.readUnsignedShort()
        val utf8 = arrayOfNulls<String>(cpCount)
        val classNameIndex = IntArray(cpCount)
        val refClass = IntArray(cpCount)
        val refNameAndType = IntArray(cpCount)
        val natName = IntArray(cpCount)
        var i = 1
        while (i < cpCount) {
            when (val tag = stream.readUnsignedByte()) {
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
                7, 8, 16, 19, 20 -> {
                    val index = stream.readUnsignedShort()
                    if (tag == 7) classNameIndex[i] = index
                }
                9, 10, 11, 12, 17, 18 -> {
                    val first = stream.readUnsignedShort()
                    val second = stream.readUnsignedShort()
                    if (tag == 9 || tag == 10 || tag == 11) {
                        refClass[i] = first
                        refNameAndType[i] = second
                    } else if (tag == 12) {
                        natName[i] = first
                    }
                }
                15 -> stream.skipBytes(3)
                else -> fail("unsupported constant-pool tag $tag in ${owner.name}")
            }
            i++
        }
        fun methodNameOf(ref: Int): Pair<String, String>? {
            if (ref <= 0 || ref >= cpCount) return null
            val cls = classNameIndex[refClass[ref]]
            val nat = refNameAndType[ref]
            if (cls <= 0 || nat <= 0) return null
            return Pair(utf8[cls] ?: return null, utf8[natName[nat]] ?: return null)
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
        val found = mutableSetOf<Pair<String, String>>()
        var matched = 0
        repeat(methodCount) {
            stream.skipBytes(2)
            val name = utf8[stream.readUnsignedShort()]
            stream.skipBytes(2)
            val attrs = stream.readUnsignedShort()
            repeat(attrs) {
                val attrName = utf8[stream.readUnsignedShort()]
                val len = stream.readInt()
                if (name == methodName && attrName == "Code") {
                    matched++
                    // Code: max_stack(2) + max_locals(2) + code_length(4) + code + handlers + attrs.
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
        if (matched == 0) fail("method $methodName not found in ${owner.name}")
        return found
    }

    private fun scanInvokes(
        code: ByteArray,
        resolve: (Int) -> Pair<String, String>?,
    ): Set<Pair<String, String>> {
        val calls = mutableSetOf<Pair<String, String>>()
        var pos = 0
        while (pos < code.size) {
            val op = code[pos].toInt() and 0xFF
            if (op == 0xB6 || op == 0xB7 || op == 0xB8 || op == 0xB9) {
                val index = ((code[pos + 1].toInt() and 0xFF) shl 8) or (code[pos + 2].toInt() and 0xFF)
                resolve(index)?.let(calls::add)
            }
            // Never stand still: a corrupt length must skip one byte, not spin.
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
            // Switch padding aligns the word after the opcode to 4 bytes.
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
