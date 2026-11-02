package com.github.ahatem.qtranslate.ui.swing.settings

/** Keeps dialog edits separate from persisted values, including writes still in progress. */
internal class StagedSecret(initial: String = "") {
    private var persistedValue = initial
    private var stagedValue = initial
    private var edited = false
    private var written = false

    @Synchronized fun read(): String = stagedValue
    @Synchronized fun stage(value: String) { stagedValue = value; edited = true }
    @Synchronized fun discard() { stagedValue = persistedValue }
    @Synchronized fun hasPendingChanges(): Boolean = stagedValue != persistedValue
    @Synchronized fun snapshot(): String? = stagedValue.takeIf { it != persistedValue }
    @Synchronized fun initialize(value: String) {
        if (!written) {
            persistedValue = value
            if (!edited) stagedValue = value
        }
    }

    suspend fun persistSnapshot(value: String?, write: suspend (String) -> Unit): Throwable? {
        if (value == null) return null
        return try {
            write(value)
            synchronized(this) { persistedValue = value; written = true }
            null
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            error
        }
    }

    suspend fun persist(write: suspend (String) -> Unit): Throwable? = persistSnapshot(snapshot(), write)
}
