package com.github.ahatem.qtranslate.ui.swing.main.input

import io.github.ahatem.qinput.QInputKey

/**
 * Derives the physical keys that must read up before a synthetic chord may be injected.
 *
 * A chord is not contaminated by its own modifier family: pressing Ctrl for a Ctrl+C while
 * Ctrl is already held still leaves the target observing Ctrl+C, because the native injector
 * skips an already-held chord modifier and never releases it. Every *other* modifier can
 * change what the target observes — a held Shift turns Ctrl+C into Ctrl+Shift+C (the browser's
 * DevTools shortcut), a held Alt or Meta changes it again — so all of them must be up.
 *
 * Both sides of every modifier are watched, because the native backends distinguish them —
 * Windows key codes, X11 keycodes and macOS key codes all separate left from right — so
 * watching only the left-hand usage would miss a physically held right-hand modifier and let
 * it contaminate the chord.
 *
 * The exemption is a property of the native injection path. The AWT Robot fallback presses and
 * releases the chord modifier unconditionally, so a physically held modifier of the chord's own
 * family is released by that fallback — pre-existing behavior of that injector, unchanged here.
 */
internal object ChordNeutralization {

    /**
     * The watched set for a chord: the chord's main key plus every modifier outside the
     * chord's own modifier family.
     *
     * [chordModifiers] are the portable usages the injector presses itself. The main key is
     * watched too: a key the user is still holding would make the injected chord something
     * else (and would make the injector fight the user's own key).
     */
    fun watchedSet(chordModifiers: List<Int>, key: Int): List<Int> {
        val exempt = chordModifiers.toSet()
        val contaminants = QInputKey.ALL_MODIFIERS.filterNot { usage ->
            exempt.any { QInputKey.isSameModifierFamily(it, usage) }
        }
        return (listOf(key) + contaminants).distinct()
    }
}
