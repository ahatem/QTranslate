---
name: qtranslate-swing-ui
description: Swing/MVI workflow for QTranslate UI work covering EDT safety, state/intent separation, focus, keyboard, fonts, DPI, RTL, and accessibility. Use for any ui-swing change.
---

# QTranslate Swing UI

## Rules

- Views are dumb: implement `Renderable<State>`, render state, dispatch
  intents. No business logic, no direct store internals, no I/O in `render()`.
- `render()` always on the EDT (`Dispatchers.Swing`). File/network work goes
  to `Dispatchers.IO`; compute to `Dispatchers.Default`. Never block the EDT.
- Follow `*State` / `*Intent` / `*Event` / `*Panel|*View|*Dialog` /
  `*StateMapping` naming. Settings edits use draft semantics (`UpdateDraft`,
  then `SaveChanges`/`CancelChanges`).
- Focus ownership is explicit; selection must survive background windows;
  keep shortcuts consistent (`Ctrl+Q`, `Ctrl+D`, `Ctrl+L`, `Ctrl+Shift+T` …).
- Genuine RTL mirroring (`LINE_START`, mirrored panes/borders), verified with
  an RTL locale — never text-alignment-only.
- Fonts must cover multilingual/fallback rendering; never enumerate fonts on
  the EDT. Custom painting uses DPI-scaled dimensions, never fixed pixels.
- Platform-sensitive rendering/accessibility: test the affected OS when the
  change touches it; state what was NOT verified on other platforms.

## Verification

Headless unit tests for models/mappings; the real running app with
before/after screenshots for anything visual (PR requirement). `git diff
--check` always.
