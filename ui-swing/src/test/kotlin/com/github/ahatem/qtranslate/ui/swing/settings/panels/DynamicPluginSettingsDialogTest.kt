package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.plugin.PluginSettings
import com.github.ahatem.qtranslate.api.settings.PluginAction
import com.github.ahatem.qtranslate.api.settings.Setting
import com.github.ahatem.qtranslate.api.settings.SettingType
import com.github.ahatem.qtranslate.core.localization.LanguageTomlParser
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.plugin.settings.DropdownSetting
import com.github.ahatem.qtranslate.core.plugin.settings.PluginActionSchema
import com.github.ahatem.qtranslate.core.plugin.settings.PluginSettingsGroup
import com.github.ahatem.qtranslate.core.plugin.settings.PluginSettingsModel
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.io.TempDir
import java.awt.GraphicsEnvironment
import java.io.File
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DynamicPluginSettingsDialogTest {

    @TempDir
    lateinit var tempDir: File

    private val logger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    private val localizer by lazy {
        LocalizationManager(tempDir, LanguageTomlParser(), logger)
    }

    class TestModelManager {
        private val installedModels = mutableSetOf("model-a")
        private val downloadingModels = mutableSetOf<String>()

        fun isInstalled(name: String): Boolean = installedModels.contains(name)
        fun isDownloading(name: String): Boolean = downloadingModels.contains(name)
        fun currentlyDownloadingModel(): String? = downloadingModels.firstOrNull()

        fun setDownloading(name: String, downloading: Boolean) {
            if (downloading) downloadingModels.add(name) else downloadingModels.remove(name)
        }
    }

    data class FakeSettings(
        @field:Setting(
            label = "Model",
            type = SettingType.DROPDOWN,
            options = "model-a,model-b",
            defaultValue = "model-a"
        )
        var selectedModel: String = "model-a"
    ) : PluginSettings.Configurable() {

        @Transient
        var manager: TestModelManager? = null

        @PluginAction(
            label = "Download Model",
            order = 1
        )
        fun downloadModel(): String? = "downloading"

        fun downloadModelVisible(): Boolean {
            val mgr = manager ?: return true
            return !mgr.isInstalled(selectedModel)
        }

        fun downloadModelEnabled(): Boolean {
            val mgr = manager ?: return true
            return !mgr.isDownloading(selectedModel)
        }

        fun downloadModelLabel(): String {
            val mgr = manager ?: return "Download Model"
            val active = mgr.currentlyDownloadingModel()
            return if (active != null) "Downloading: $active..." else "Download Model"
        }

        fun selectedModelDisplayLabel(name: String): String {
            val mgr = manager ?: return name
            return if (mgr.isInstalled(name)) "$name (installed)" else name
        }
    }

    @Test
    fun `EDT test - download A then select B shows Download button visible and enabled`() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Requires graphical environment or Xvfb")

        var testDialog: DynamicPluginSettingsDialog? = null
        var downloadBtn: JButton? = null
        var modelCombo: JComboBox<*>? = null

        val mgr = TestModelManager()
        val settings = FakeSettings().apply {
            manager = mgr
            selectedModel = "model-a"
        }

        val settingSchema = DropdownSetting(
            propertyName = "selectedModel",
            label = "Model",
            description = "",
            order = 1,
            isRequired = false,
            options = listOf("model-a", "model-b"),
            defaultValue = "model-a",
            currentValue = "model-a"
        )

        val actionSchema = PluginActionSchema(
            methodName = "downloadModel",
            label = "Download Model",
            tooltip = "",
            order = 1,
            group = ""
        )

        val group = PluginSettingsGroup(
            key = "",
            title = "",
            order = 0,
            fields = listOf(settingSchema),
            actions = listOf(actionSchema)
        )

        val model = PluginSettingsModel(
            settingsClass = FakeSettings::class.java,
            schema = listOf(settingSchema),
            groups = listOf(group)
        )

        SwingUtilities.invokeAndWait {
            val dialog = DynamicPluginSettingsDialog(
                owner = null,
                pluginName = "Test Plugin",
                localizationManager = localizer,
                settingsModel = model,
                settingsInstance = settings,
                onSave = {}
            )
            testDialog = dialog
            downloadBtn = dialog.getActionButton("downloadModel")
            @Suppress("UNCHECKED_CAST")
            modelCombo = dialog.getComponent("selectedModel") as? JComboBox<*>
        }

        assertNotNull(testDialog)
        assertNotNull(downloadBtn)
        assertNotNull(modelCombo)

        // Initially Model A is selected -> installed in mgr -> Download button hidden
        SwingUtilities.invokeAndWait {
            assertEquals("model-a", modelCombo?.selectedItem)
            assertFalse(downloadBtn!!.isVisible, "Download button should be hidden for installed model A")
        }

        // Simulate background download started for model A
        mgr.setDownloading("model-a", true)

        // Select uninstalled Model B in the combo box
        SwingUtilities.invokeAndWait {
            modelCombo?.selectedItem = "model-b"
        }

        // Verify: Download button MUST be visible and enabled for Model B
        SwingUtilities.invokeAndWait {
            assertTrue(downloadBtn!!.isVisible, "Download button must become visible after selecting uninstalled model B")
            assertTrue(downloadBtn!!.isEnabled, "Download button must be enabled for Model B while Model A downloads")
            assertEquals("Downloading: model-a...", downloadBtn!!.text)

            // Test custom display label rendered in combo
            @Suppress("UNCHECKED_CAST")
            val renderer = modelCombo?.renderer as? javax.swing.ListCellRenderer<Any?>
            assertNotNull(renderer)
            val dummyList = javax.swing.JList<Any?>()
            val componentA = renderer.getListCellRendererComponent(dummyList, "model-a", 0, false, false) as javax.swing.JLabel
            assertEquals("model-a (installed)", componentA.text)
            val componentB = renderer.getListCellRendererComponent(dummyList, "model-b", 1, false, false) as javax.swing.JLabel
            assertEquals("model-b", componentB.text)
        }

        // Clean up
        SwingUtilities.invokeAndWait {
            testDialog?.dispose()
        }
    }
}
