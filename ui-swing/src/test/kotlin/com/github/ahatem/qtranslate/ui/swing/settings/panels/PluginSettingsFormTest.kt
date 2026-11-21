package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.plugin.PluginSettings
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.core.localization.LanguageTomlParser
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.plugin.ServiceValidation
import com.github.ahatem.qtranslate.core.plugin.settings.BooleanSetting
import com.github.ahatem.qtranslate.core.plugin.settings.DropdownSetting
import com.github.ahatem.qtranslate.core.plugin.settings.PasswordSetting
import com.github.ahatem.qtranslate.core.plugin.settings.PluginSettingsGroup
import com.github.ahatem.qtranslate.core.plugin.settings.PluginSettingsModel
import com.github.ahatem.qtranslate.core.plugin.settings.SettingSchema
import com.github.ahatem.qtranslate.core.plugin.settings.ShowIfRule
import com.github.ahatem.qtranslate.core.plugin.settings.TextSetting
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.awt.Component
import java.awt.Container
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPasswordField
import javax.swing.JTextField
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The plugin settings form the Plugins page shows inline and the service menu shows in a window:
 * one control per declared field, the values on screen handed to the host's save and check
 * callbacks, their outcome reported on the form's own status line, and only the actions the host
 * actually supplied.
 *
 * Nothing here presses Save in a state that warns through a `JOptionPane` — a missing required
 * field and a failed save both do, and a modal dialog has no one to answer it in a test run. The
 * disabled state that prevents that click is asserted instead.
 */
class PluginSettingsFormTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val logger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    private val localizer by lazy {
        LocalizationManager(
            Files.createTempDirectory("qtranslate-plugin-form").toFile(),
            LanguageTomlParser(),
            logger,
        )
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private fun <T> onEdt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        val result = arrayOfNulls<Any?>(1)
        SwingUtilities.invokeAndWait { result[0] = block() }
        @Suppress("UNCHECKED_CAST")
        return result[0] as T
    }

    // ── The form's schema ─────────────────────────────────────────────────────

    @Test
    fun `the form builds one control for every field in the schema`() {
        val form = newForm(
            text("apiUrl", "API endpoint", value = "https://api.example"),
            password("apiKey", "API key", value = "s3cret"),
            checkbox("cache", "Cache answers", value = true),
            dropdown("mode", "Mode", options = listOf("fast", "exact"), value = "fast"),
        )

        val textFields = onEdt { descendants<JTextField>(form) }.filterNot { it is JPasswordField }
        assertEquals(
            listOf("https://api.example"),
            textFields.map { it.text },
            "the text field starts from the schema's value",
        )
        // `getText()` is deprecated on a password field; the stored characters are read instead.
        assertEquals(
            "s3cret",
            String(onEdt { descendants<JPasswordField>(form).single().password }),
            "a password field is built for a PasswordSetting",
        )
        assertTrue(
            onEdt { descendants<JCheckBox>(form).single().isSelected },
            "a checkbox is built for a BooleanSetting, showing the stored value",
        )
        assertEquals(
            "fast",
            onEdt { descendants<JComboBox<*>>(form).single().selectedItem },
            "a dropdown is built for a DropdownSetting, showing the stored value",
        )
        val labels = onEdt { descendants<JLabel>(form) }.map { it.plainText() }
        assertTrue(labels.any { it.contains("API endpoint") }, "the field's own label is shown: $labels")
        assertTrue(labels.any { it.contains("API key") }, "every field contributes its label: $labels")
    }

    // ── Saving ────────────────────────────────────────────────────────────────

    @Test
    fun `Save hands the values on screen to the host`() {
        val saved = AtomicReference<Map<String, String>?>(null)
        val form = newForm(
            text("apiUrl", "API endpoint"),
            text("region", "Region", value = "eu"),
            onSave = { values, _ ->
                saved.set(values)
                Ok(Unit)
            },
        )

        val fields = onEdt { descendants<JTextField>(form) }
        onEdt {
            fields[0].text = "https://typed.example"
            fields[1].text = "us"
            form.saveButton.doClick()
        }

        await("the host is handed the values on screen") { saved.get() != null }
        assertEquals(
            mapOf("apiUrl" to "https://typed.example", "region" to "us"),
            saved.get(),
            "one entry per field, keyed by the schema's property name and carrying what was typed",
        )
    }

    @Test
    fun `a save that succeeds is reported on the form's status line`() {
        val savedHook = AtomicInteger(0)
        val form = newForm(
            text("apiUrl", "API endpoint", value = "https://api.example"),
            onSaved = { savedHook.incrementAndGet() },
        )

        onEdt { form.saveButton.doClick() }

        val saved = localizer.getString("plugin_config.saved")
        await("the status line reads \"$saved\"") {
            onEdt { descendants<JLabel>(form).any { it.isVisible && it.text == saved } }
        }
        assertEquals(1, savedHook.get(), "and the host's after-save hook runs, which is how a window closes itself")
    }

    @Test
    fun `a required field left blank keeps Save disabled until it is filled in`() {
        val form = newForm(text("apiKey", "API key", required = true))

        assertFalse(
            onEdt { form.saveButton.isEnabled },
            "an empty required field blocks the save rather than letting it fail against the plugin",
        )

        onEdt { descendants<JTextField>(form).single().text = "typed-by-the-user" }

        assertTrue(
            onEdt { form.saveButton.isEnabled },
            "the moment the field is filled in the save is available again",
        )
    }

    // ── Conditional visibility ────────────────────────────────────────────────

    @Test
    fun `a field governed by showIf appears only when its controlling field has the required value`() {
        val form = newForm(
            dropdown("mode", "Mode", options = listOf("preset", "custom"), value = "preset"),
            text("endpoint", "Custom endpoint", showIf = ShowIfRule("mode", "custom")),
        )
        val endpoint = onEdt { descendants<JTextField>(form).single() }
        val block = onEdt { endpoint.parent }

        assertFalse(onEdt { block.isVisible }, "the dependent field starts hidden, because 'preset' is not 'custom'")

        onEdt { descendants<JComboBox<*>>(form).single().selectedItem = "custom" }

        assertTrue(onEdt { block.isVisible }, "choosing the required value reveals it without a rebuild")
    }

    // ── Host-supplied actions ─────────────────────────────────────────────────

    @Test
    fun `the Revert action appears only when the host supplies one and reports to it`() {
        val revertLabel = localizer.getString("plugin_config.revert")

        val withoutRevert = newForm(text("apiUrl", "API endpoint"))
        assertTrue(
            onEdt { descendants<JButton>(withoutRevert) }.none { it.text == revertLabel },
            "a host with nothing to revert to gets no Revert button",
        )

        val reverted = AtomicInteger(0)
        val withRevert = newForm(
            text("apiUrl", "API endpoint"),
            onRevert = { reverted.incrementAndGet() },
        )
        val revert = onEdt { descendants<JButton>(withRevert) }.single { it.text == revertLabel }

        onEdt { revert.doClick() }

        assertEquals(1, reverted.get(), "the button reports to the host's revert handler")
    }

    @Test
    fun `the Test action appears only when the host supplies one and its answer reaches the status line`() {
        val testLabel = localizer.getString("plugin_config.test_connection")

        val withoutTest = newForm(text("apiUrl", "API endpoint"))
        assertTrue(
            onEdt { descendants<JButton>(withoutTest) }.none { it.text == testLabel },
            "a plugin with nothing to check gets no Test button",
        )

        val checked = AtomicReference<Map<String, String>?>(null)
        val form = newForm(
            text("apiUrl", "API endpoint", value = "https://api.example"),
            onTestConnection = { values ->
                checked.set(values)
                listOf(ServiceValidation(serviceId = "example", serviceName = "Example", error = null))
            },
        )
        val test = onEdt { descendants<JButton>(form) }.single { it.text == testLabel }

        onEdt { test.doClick() }

        val ready = localizer.getString("plugin_config.test_ok").format(1)
        await("the check's answer reaches the status line") {
            onEdt { descendants<JLabel>(form).any { it.isVisible && it.text == ready } }
        }
        assertEquals(
            mapOf("apiUrl" to "https://api.example"),
            checked.get(),
            "the check asks about the values on screen, not the stored ones",
        )
    }

    @Test
    fun `addFooterAction puts the host's button on the form's own button row`() {
        val form = newForm(text("apiUrl", "API endpoint"))
        val cancel = JButton("Host Cancel")

        onEdt { form.addFooterAction(cancel) }

        assertTrue(
            onEdt { descendants<JButton>(form) }.contains(cancel),
            "the host's button becomes part of the form",
        )
        assertSame(
            onEdt { form.saveButton.parent },
            onEdt { cancel.parent },
            "and shares Save's row, so the form has one button bar rather than two",
        )
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    /**
     * A form over [schema] in one unnamed group, which is what a plugin without `@SettingGroup`
     * produces — the flat layout the form shows for most plugins.
     */
    private fun newForm(
        vararg schema: SettingSchema,
        onSave: suspend (Map<String, String>, Set<String>) -> Result<Unit, ServiceError> = { _, _ -> Ok(Unit) },
        onSaved: (() -> Unit)? = null,
        onRevert: (() -> Unit)? = null,
        onTestConnection: (suspend (Map<String, String>) -> List<ServiceValidation>)? = null,
    ): PluginSettingsForm {
        val form = onEdt {
            PluginSettingsForm(
                localizationManager = localizer,
                settingsModel = PluginSettingsModel(
                    settingsClass = PluginSettings.Configurable::class.java,
                    schema = schema.toList(),
                    groups = listOf(PluginSettingsGroup(key = "", title = "", fields = schema.toList())),
                ),
                scope = scope,
                onSave = onSave,
                onSaved = onSaved,
                onRevert = onRevert,
                onTestConnection = onTestConnection,
            )
        }
        // The form runs its first validation and visibility pass from the event queue, because the
        // fields have no layout when the constructor returns. Let that pass run before reading
        // anything about the form.
        onEdt { }
        return form
    }

    private fun text(
        name: String,
        label: String,
        value: String = "",
        required: Boolean = false,
        showIf: ShowIfRule? = null,
    ) = TextSetting(
        propertyName = name,
        label = label,
        description = "",
        order = 0,
        isRequired = required,
        currentValue = value,
        defaultValue = value,
        showIf = showIf,
    )

    private fun password(name: String, label: String, value: String = "") = PasswordSetting(
        propertyName = name,
        label = label,
        description = "",
        order = 0,
        isRequired = false,
        currentValue = value,
        defaultValue = value,
    )

    private fun checkbox(name: String, label: String, value: Boolean) = BooleanSetting(
        propertyName = name,
        label = label,
        description = "",
        order = 0,
        isRequired = false,
        currentValue = value.toString(),
        defaultValue = value.toString(),
    )

    private fun dropdown(
        name: String,
        label: String,
        options: List<String>,
        value: String,
        showIf: ShowIfRule? = null,
    ) = DropdownSetting(
        propertyName = name,
        label = label,
        description = "",
        order = 0,
        isRequired = false,
        currentValue = value,
        defaultValue = value,
        showIf = showIf,
        options = options,
    )

    // ── Waiting and walking ───────────────────────────────────────────────────

    /**
     * Waits for [condition] for at most [AWAIT_MILLIS].
     *
     * The form saves and tests through the host's scope, so the outcome lands on the Swing thread a
     * moment after the button is pressed. The wait is bounded: a save that never reports is one
     * failing test with a clear message rather than a suite that hangs.
     */
    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + AWAIT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(5)
        }
        fail("timed out after ${AWAIT_MILLIS}ms waiting until $what")
    }

    private inline fun <reified T : Component> descendants(root: Container): List<T> {
        val result = mutableListOf<T>()
        val queue = ArrayDeque<Component>()
        queue.addLast(root)
        while (queue.isNotEmpty()) {
            val component = queue.removeFirst()
            if (component is T) result += component
            if (component is Container) queue.addAll(component.components)
        }
        return result
    }

    private companion object {
        /** Generous next to a save that does anything at all, short enough to fail quickly. */
        const val AWAIT_MILLIS = 2000L
    }
}
