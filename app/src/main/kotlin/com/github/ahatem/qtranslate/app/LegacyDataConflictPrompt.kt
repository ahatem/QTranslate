package com.github.ahatem.qtranslate.app

import java.awt.GraphicsEnvironment
import java.io.File
import javax.swing.JOptionPane

/**
 * Asks which of two populated data directories to use, before anything is read from either.
 *
 * Runs before the logger exists and before any repository is constructed, so neither directory has
 * been touched. Both are shown in full because the user cannot tell them apart by name.
 *
 * Whichever way it is answered, nothing is merged, moved or deleted — the directory not chosen is
 * left exactly as it is, so changing the decision later costs a restart and one deleted marker file.
 *
 * **Closing the dialog chooses the OS-standard location**, the same as the first button, so a
 * dismissed dialog still starts and still settles the question instead of reappearing on every
 * launch.
 */
class LegacyDataConflictPrompt : LegacyDataConflictResolver {

    override fun resolve(legacyPortableRoot: File, installedRoot: File): File {
        // Headless: the release probe always supplies an explicit data directory and so never gets
        // here, but a scheduled or service launch must still start rather than block on a dialog.
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println(
                "QTranslate found existing user data in two locations and cannot ask which to use:\n" +
                        "  beside the application: ${legacyPortableRoot.absolutePath}\n" +
                        "  user data location:  ${installedRoot.absolutePath}\n" +
                        "Using the user data location and recording that choice. " +
                        "Neither directory was changed."
            )
            return installedRoot
        }

        val choice = JOptionPane.showOptionDialog(
            null,
            "QTranslate found existing data in two locations and cannot tell which one you want to " +
                    "keep using.\n\n" +
                    "Beside the application:\n${legacyPortableRoot.absolutePath}\n\n" +
                    "User data location:\n${installedRoot.absolutePath}\n\n" +
                    "Your choice is remembered, so this will not be asked again. Neither location " +
                    "will be changed or deleted — the one you do not choose is left where it is.",
            "Choose where QTranslate should store your data",
            JOptionPane.DEFAULT_OPTION,
            JOptionPane.QUESTION_MESSAGE,
            null,
            arrayOf(
                "Use the user data location",
                "Keep using the data beside the application"
            ),
            null
        )
        // Closing the dialog is not a decision to keep the legacy data; it falls back to the default
        // rather than guessing at the older location.
        return if (choice == 1) legacyPortableRoot else installedRoot
    }
}

/** Reports a data directory that could not be created or written. */
object AppDataFailureDialog {
    fun show(failure: AppDataLayoutException) {
        if (GraphicsEnvironment.isHeadless()) return
        runCatching {
            JOptionPane.showMessageDialog(
                null,
                "${failure.message}\n\nQTranslate has stopped rather than store your data somewhere " +
                        "you did not choose.",
                "QTranslate cannot use its data folder",
                JOptionPane.ERROR_MESSAGE
            )
        }
    }
}