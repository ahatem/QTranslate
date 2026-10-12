---
name: qtranslate-native-input
description: High-risk QInput/global-input workflow covering the Java/native boundary, hotkeys, Double Ctrl, selection, injection, and platform matrices. Use for any global input change.
---

# QTranslate Native Input

Automatically R3. Global input mistakes break the app on OSes you cannot see.

## Boundaries

- Java side owns behavior (`MainGlobalKeyListener`, `HotkeyRegistrationLedger`,
  `DoubleCtrlDetector`, `CopyInjector`/`PasteInjector`); the QInput
  native library owns capture. The contract is `GlobalInputBackend` —
  never bypass it, never touch the native boundary directly from UI code.
- Interface-parameter semantics are versioned: a meaning change bumps the
  ABI/version even if the signature is unchanged.
- Never synthesize release of a physically-held key; never let a held
  modifier transform an injected chord. Clocks for timing are monotonic.
- Platform logic is factored to be testable on any host (predicates, traits,
  target-independent modules) — not "testable only on the OS that uses it".

## Matrix (state per platform)

Windows / macOS / Linux-X11 / Linux-Wayland: hotkey registration,
raw key/mouse events, enable/disable lifecycle, shutdown, Double Ctrl,
selection/outside-click behavior, injection fallback. Mark each
`verified / reasoned-only / n/a` — reasoned-only is never presented as tested.

## Tests

Unit-test the Java-side predicates and ledger logic headlessly; platform
behavior needs the real OS (WSL+Xvfb for Linux spot-checks per repo history).
