package com.github.ahatem.qtranslate.ui.swing.main.input

/** Controllable [GlobalInputBackend] that records everything and replays scripted events. */
internal class FakeGlobalInputBackend(
    override var capabilities: InputCapabilities =
        InputCapabilities(
            hotkeys = true,
            rawKeyboard = true,
            rawMouseButtons = true,
            rawMouseMotion = true,
            portalHotkeys = false,
            injectedFlag = true,
            keyboardInjection = false,
            keyState = true
        ),
    override var backendName: String = "fake",
    var failOnApply: Throwable? = null,
    /** When positive, the next N apply calls throw [failOnApply] (or a default) then succeed. */
    var failNextApplies: Int = 0,
) : GlobalInputBackend {

    val applied = mutableListOf<List<GlobalInputBackend.HotkeyRegistration>>()
    var rawMask: Triple<Boolean, Boolean, Boolean>? = null
    var eventListener: ((GlobalInputEvent) -> Unit)? = null
    var closeCount = 0
    var closed = false
    override var supportsInjection: Boolean = false
    var chordResult: Boolean = true
    var chordThrows: Throwable? = null
    val chords = mutableListOf<Pair<List<Int>, Int>>()

    /**
     * Accelerators whose native release is refused: they keep firing after leaving the desired
     * set, modelling a platform unregister that failed. The registration stays installed, exactly
     * as the OS would leave it.
     */
    val refusedUnregister = mutableSetOf<String>()

    /** Accelerator to token for every registration currently installed, desired or left over. */
    private val installed = mutableMapOf<String, Long>()

    /** Accelerator to token for registrations still installed although no longer desired. */
    private val leftovers = mutableMapOf<String, Long>()

    /** Invoked inside [applyHotkeys] after the new set is recorded, before it returns. */
    var emitDuringApply: ((FakeGlobalInputBackend) -> Unit)? = null

    /**
     * Makes the next apply install the desired set and then throw, modelling a transport failure
     * that leaves speculative registrations installed: the desired set is live in the OS although
     * the apply reports failure. The rollback apply that follows reinstalls the previous set but
     * does *not* unwind the speculative registrations, exactly what a real platform leaves behind
     * when the failed attempt cannot be unwound.
     */
    var throwAfterPartialApply = false

    /** Armed by [throwAfterPartialApply]: the following apply is the rollback, not a new attempt. */
    private var rollbackPending = false

    override fun applyHotkeys(registrations: List<GlobalInputBackend.HotkeyRegistration>): ApplyResult {
        if (failNextApplies > 0) {
            failNextApplies--
            throw failOnApply ?: RuntimeException("backend apply failed")
        }
        failOnApply?.let { throw it }

        val desired = registrations.associate { it.accelerator to it.id }
        val isRollback = rollbackPending
        rollbackPending = false
        if (!isRollback) {
            // A registration that left the desired set is released, unless the platform refuses,
            // in which case it stays installed and can still fire. This is the whole point of the
            // fake: no amount of calling applyHotkeys makes a refused registration go away until
            // the platform starts cooperating. A rollback reinstalls without unwinding, so
            // speculative registrations from the failed attempt stay installed.
            for (accelerator in installed.keys.toList()) {
                if (accelerator in desired) continue
                if (accelerator in refusedUnregister) continue
                installed.remove(accelerator)
                leftovers.remove(accelerator)
            }
        }
        for ((accelerator, token) in desired) {
            // A rollback must not overwrite the speculative token: the failed attempt's token is
            // what the OS would actually echo for the still-installed registration.
            if (isRollback && accelerator in installed) continue
            installed[accelerator] = token
        }
        // Anything installed but not desired is the residue of a refused or unwound release.
        leftovers.clear()
        for ((accelerator, token) in installed) {
            if (accelerator !in desired) leftovers[accelerator] = token
        }
        applied += registrations.toList()
        emitDuringApply?.invoke(this)
        if (throwAfterPartialApply) {
            throwAfterPartialApply = false
            rollbackPending = true
            throw RuntimeException("backend apply failed after installing the set")
        }
        val leftover = leftovers.keys.sorted()
        return if (leftover.isEmpty()) ApplyResult.Clean else ApplyResult.Degraded(leftover)
    }
    /** Accelerators the platform still holds after we stopped wanting them. */
    fun leftoverAccelerators(): Set<String> = leftovers.keys.toSet()

    /** Number of registrations the platform still holds although they are no longer desired. */
    fun leftoverCount(): Int = leftovers.size

    /**
     * Emits the event a leftover (unreleased) registration would produce, exactly as the OS would
     * if the user pressed the shortcut that could not be unregistered.
     */
    fun emitLeftover(accelerator: String) {
        val token = leftovers[accelerator]
            ?: error("$accelerator is not a leftover registration; leftovers=${leftovers.keys}")
        emit(GlobalInputEvent.Hotkey(token))
    }

    /** Emits the event for the currently installed registration of [accelerator]. */
    fun emitInstalled(accelerator: String) {
        val token = installed[accelerator]
            ?: error("$accelerator is not installed; installed=${installed.keys}")
        emit(GlobalInputEvent.Hotkey(token))
    }

    override fun setRawMask(keyboard: Boolean, mouseButtons: Boolean, mouseMotion: Boolean) {
        rawMask = Triple(keyboard, mouseButtons, mouseMotion)
    }

    override fun setListener(listener: (GlobalInputEvent) -> Unit) {
        this.eventListener = listener
    }

    override fun sendChord(modifiers: List<Int>, key: Int): Boolean {
        chordThrows?.let { throw it }
        chords += modifiers.toList() to key
        return chordResult
    }

    /** Physical key state scripted by tests: portable QInput usage to down-state. */
    var keyDown: MutableMap<Int, Boolean> = mutableMapOf()

    /** Number of single-key state queries served, so tests can prove a path never neutralizes. */
    var keyStateQueries = 0

    override fun isKeyDown(key: Int): Boolean {
        keyStateQueries++
        return keyDown[key] ?: false
    }

    /** Delivers an event like the native dispatcher would; silent once closed. */
    fun emit(event: GlobalInputEvent) {
        if (!closed) eventListener?.invoke(event)
    }

    /**
     * Invoked inside [close], before it takes effect. A concurrency test can use this to pause
     * shutdown mid-transaction (the mirror of [emitDuringApply] pausing an apply), so it can
     * force and observe a reconcile racing against an in-progress shutdown from either direction.
     */
    var onClose: (() -> Unit)? = null

    override fun close() {
        onClose?.invoke()
        closed = true
        closeCount++
    }

    fun lastApplied(): List<GlobalInputBackend.HotkeyRegistration> = applied.lastOrNull() ?: emptyList()
}
