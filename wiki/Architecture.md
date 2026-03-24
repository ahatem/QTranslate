# Architecture

QTranslate is structured around Clean Architecture with an MVI (Model-View-Intent) pattern for the UI layer. This document explains the structure and the reasoning behind the key decisions.

---

## Module overview

```
┌─────────────────────────────────────────────┐
│                   :app                      │  ← composition root, main()
└─────────────────┬───────────────────────────┘
                  │ depends on
┌─────────────────▼───────────────────────────┐
│               :ui-swing                     │  ← Swing UI, Renderable<State>
└─────────────────┬───────────────────────────┘
                  │
┌─────────────────▼───────────────────────────┐
│                 :core                       │  ← business logic, stores, use cases
└─────────────────┬───────────────────────────┘
                  │
┌─────────────────▼───────────────────────────┐
│                  :api                       │  ← interfaces only
└─────────────────────────────────────────────┘
         ▲
         │  plugins depend only on :api
┌────────┴────────────────────────────────────┐
│          :plugins:google-services           │
│          :plugins:bing-services             │
│          :plugins:...                       │
└─────────────────────────────────────────────┘
```

**Dependency rule:** lower layers never import from upper layers. `:core` has no Swing imports. `:api` imports only what its own interfaces need (coroutines for suspend functions, kotlin-result for `Result`). Plugins only import `:api`.

---

## :api — the plugin contract

Everything a plugin author needs lives here: service interfaces (`Translator`, `TextToSpeech`, `OCR`, `SpellChecker`, `Dictionary`, `Summarizer`, `Rewriter`), the `Plugin` base class, `PluginContext`, `PluginSettings`, request/response data classes, and `ServiceError`.

This module is intentionally minimal. Adding something to `:api` is a commitment — it becomes part of the public plugin API and breaking changes require a major version bump.

---

## :core — business logic

### MVI stores

Each screen has a `Store<State, Intent, Event>`:

- **`MainStore`** — translation screen state (input text, results, history, loading)
- **`SettingsStore`** — settings state (working draft, saved configuration)

Stores hold a `MutableStateFlow<State>` and expose it as `StateFlow<State>`. They accept `Intent`s via `dispatch()` and emit one-shot `Event`s via a `Channel`.

### Use cases

Business logic lives in use cases, not stores. Each use case does one thing:

- `TranslateTextUseCase` — calls the active translator, manages history, orchestrates extra output
- `SummarizeUseCase` — calls the active summarizer with the configured `SummaryLength`
- `RewriteUseCase` — calls the active rewriter with the configured `RewriteStyle`
- `HandleTextToSpeechUseCase` — resolves language, calls TTS, plays audio
- `OcrAndTranslateUseCase` — extracts text from image, returns it to the store
- `PerformSpellCheckUseCase` — calls spell checker, returns corrections
- `SelectActiveServiceUseCase` — observes available services, resolves language lists
- `CheckForUpdatesUseCase` — queries GitHub releases API

### Plugin subsystem

`PluginManager` orchestrates:
- `PluginLoader` — scans the `plugins/` directory, validates manifests
- `PluginRegistry` — thread-safe in-memory store of loaded plugins
- `PluginLifecycleHandler` — calls `onInitialize`, `onEnable`, `onDisable`, `onShutdown`
- `PluginInstaller` — copies JARs, handles install/uninstall/verification
- `PluginSettingsManager` — reflects `@field:Setting` annotations to build settings UI

### Settings

`SettingsRepository` persists configuration to DataStore. `SettingsStore` holds a working copy (draft) that can be edited without committing — Apply saves it, Cancel discards it.

`ActiveServiceManager` resolves "which service should I use?" from the current preset and live service registry.

---

## :ui-swing — the view layer

### Renderable

Every UI component that reacts to state implements:

```kotlin
interface Renderable<S : UiState> {
    fun render(state: S)
}
```

`render()` is always called on `Dispatchers.Swing`. Components never call business logic directly — they dispatch intents.

### Intent dispatch

User actions produce intents:

```kotlin
// In a button listener:
mainStore.dispatch(MainIntent.Translate())
```

The store processes the intent, updates state, and emits a new state snapshot. `render()` is called with the new state.

### Threading

- State collection: `Dispatchers.Swing` via `withContext(Dispatchers.Swing)`
- Network/IO: `Dispatchers.IO`
- Business logic: `Dispatchers.Default`

Blocking calls (`Files.copy`, JDBC, synchronous HTTP) must always be wrapped in `withContext(Dispatchers.IO)`.

---

## Error handling

Expected failures (network errors, invalid API keys, parse failures) are returned as `Result<T, ServiceError>` using the `kotlin-result` library. Raw exceptions are only for truly unexpected conditions (programming errors, OOM).

`ServiceError` is a sealed class:
- `NetworkError` — connectivity issues
- `AuthError` — invalid or expired credentials
- `RateLimitError` — API quota exceeded (retryable)
- `InvalidResponseError` — unexpected API response format
- `UnknownError` — catch-all for unexpected failures

---

## Settings — draft and commit

`SettingsStore` maintains two copies of the configuration:
- **Saved** — the persisted configuration loaded from DataStore on startup
- **Working draft** — an in-memory copy the user edits in the settings dialog

`UpdateDraft` intents mutate the working draft without touching the saved config. `SaveChanges` persists the draft. `CancelChanges` discards it and reverts to the saved copy. This means the user can freely edit settings and cancel without any side effects — nothing is written to disk until they explicitly save.

`applyDraft(store) { it.copy(...) }` is the correct way to update the draft from a settings panel.

Some settings take effect immediately without going through the draft cycle — `CloseButtonBehavior` is one example. When the user picks "remember my choice" in the close dialog, the app dispatches `ToggleSetting` + `SaveChanges` directly so the preference persists without the user having to open Settings. Use `ToggleSetting` for this pattern; use `UpdateDraft` for anything that should be subject to OK/Cancel/Apply. It reads the current working draft atomically and dispatches `UpdateDraft` with the result — avoids stale reads when two fields change in rapid succession.

---

## RTL support

QTranslate mirrors its entire layout for RTL languages (Arabic, Hebrew, Farsi, etc.). The mechanism:

1. `LocalizationManager` exposes `isRtl: Boolean` based on the active language's `[meta] rtl = true` field
2. On language change, `MainAppFrame` calls `applyComponentOrientation(ComponentOrientation.RIGHT_TO_LEFT)` on the root pane — Swing propagates this down the component tree
3. Layout managers that respect orientation (`BorderLayout.LINE_START/LINE_END`, `FlowLayout.LEADING/TRAILING`, `GridBagConstraints.LINE_START`) flip automatically
4. `LayoutManager.switchLayout(id, isRtl)` rebuilds the main content layout, passing `isRtl` so `SideBySideLayout` swaps the input/output panel positions

The key rule: never use absolute constants (`WEST`, `EAST`, `LEFT`, `RIGHT`) in layout code — always use orientation-relative ones (`LINE_START`, `LINE_END`, `LEADING`, `TRAILING`).

---

## Hotkey system

Hotkeys are data-driven — stored in `Configuration.hotkeys` as `List<HotkeyBinding>`. Each binding holds a `keyCode`, `modifiers`, `HotkeyAction`, and `HotkeyScope`.

`HotkeyScope` controls where the hotkey fires:
- `GLOBAL` — registered with jKeymaster, fires system-wide even when QTranslate is not focused
- `LOCAL` — registered via Swing `InputMap`/`ActionMap`, fires only when QTranslate has focus

`MainGlobalKeyListener` splits bindings by scope: GLOBAL bindings go to jKeymaster, LOCAL bindings are returned via `getLocalBindings()` for `MainAppFrame` to register on the `rootPane`.

`SHOW_MAIN_WINDOW` is special — it uses a double-Ctrl sequence via JNativeHook, not a standard KeyStroke.

---

## Notification system

`NotificationBus` is a `SharedFlow`-based pub/sub channel for cross-component notifications. `PluginManager` posts to it when plugins fail to load, need verification, or when no plugins are found at all. `MainAppFrame` subscribes and routes messages to the status bar via `StatusBarController.handleNotification()`.

This keeps `PluginManager` decoupled from the UI — it posts a domain notification, not a UI event.

---

## Plugin loading lifecycle

```
PluginLoader.loadPluginsFromDirectory()
  → parse plugin.json (PluginManifest)
  → verify API version compatibility
  → ServiceLoader.load(Plugin::class, pluginClassLoader)
  → PluginRegistry.put(container)

PluginLifecycleHandler
  → initialize(container)   ← Plugin.onInitialize(context)
  → enable(container)       ← Plugin.onEnable()
  [user action]
  → disable(container)      ← Plugin.onDisable()
  → shutdown(container)     ← Plugin.onShutdown()
```

Plugins whose JAR hash changed since the last run are held in `AWAITING_VERIFICATION` until the user confirms the change.