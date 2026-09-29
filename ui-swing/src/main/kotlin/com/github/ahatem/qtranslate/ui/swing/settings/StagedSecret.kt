package com.github.ahatem.qtranslate.ui.swing.settings

/** Keeps a dialog-edited secret separate from its persisted value until a save succeeds. */
internal class StagedSecret(initial: String = "") {
    private var persistedValue = initial
    private var stagedValue = initial

    fun read(): String = stagedValue

    fun stage(value: String) {
        stagedValue = value
    }

    fun discard() {
        stagedValue = persistedValue
    }

    fun hasPendingChanges(): Boolean = stagedValue != persistedValue

    suspend fun persist(write: suspend (String) -> Unit): Throwable? {
        if (!hasPendingChanges()) return null
        val value = stagedValue
        return try {
            write(value)
            persistedValue = value
            null
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            error
        }
    }
}
