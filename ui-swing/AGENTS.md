# `:ui-swing` — Swing presentation (MVI)

Views render feature state and dispatch actions. Swing owns focus, layout and
window mechanics; application features own business policy. Follow the
[UI workflow](../docs/Development.md#swing-and-native-input).

- State-driven product views implement `Renderable<State>`; `render()` runs on the EDT
  (`Dispatchers.Swing`). Never block the EDT: no I/O, no network, no waiting —
  push work to `Dispatchers.IO`/`Default` and render the result.
- Naming: `*State` / `*Intent` (sealed) / `*Event` (consumed once) /
  `*Panel|*View|*Dialog` / `*StateMapping` / `*Controller|*Coordinator`.
- Focus ownership is explicit; selection behavior must survive background
  windows; keyboard shortcuts stay consistent with the defaults in
  `HotkeyBinding.DEFAULTS` (`Ctrl+Q` Quick Translate, `Ctrl+D` dictionary,
  `Ctrl+L` cycles target languages, `Ctrl+Shift+T` replace-with-translation).
  Note the `MODERN` preset in `HotkeyPresets.kt` rebinds several of these, and
  pinned languages (`Configuration.pinnedLanguages`) are a separate concept
  from the languages `Ctrl+L` cycles.
- RTL is genuine mirroring: `LINE_START`/`LINE_END`, mirrored borders, indents,
  and split panes — never text-alignment-only. Check with an RTL locale.
- Fonts: multilingual/fallback coverage matters; never let font enumeration
  block the EDT. Custom painting scales for high DPI (no fixed-pixel visuals).
- Global input (hotkeys, Double Ctrl, mouse hooks, capture/injection) is owned
  by `MainGlobalKeyListener` + `QInputBackend` behind `GlobalInputBackend`.
  That boundary is R3; follow the native-input workflow.
- Verify visual changes in the real running app with before/after screenshots
  for the PR. `captureScreenshots` needs a display; unit tests stay headless.
