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
│                  :api                       │  ← plugin contracts and values
└─────────────────────────────────────────────┘
         ▲
         │  plugins depend only on :api
┌────────┴────────────────────────────────────┐
│          :plugins:google-services           │
│          :plugins:bing-services             │
│          :plugins:...                       │
└─────────────────────────────────────────────┘
```

**Dependency rule:** lower layers never import from upper layers. `:core` has no Swing components or windows; hotkey value types and document text/image measurement are legitimate existing responsibilities. `:api` contains capability contracts, shared values/errors and pure compatibility semantics. Providers depend on `:api` and `:plugins:common`, never host implementation modules.

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

### Workspace ownership

Feature ownership is explicit in the package tree:

- `core/workspace/translation` — translation request/run ownership, comparison coordination,
  secondary output and its outcomes, and the workflow policy (`TranslationCoordinator`) that
  turns an accepted origin into auto-copy, refresh fallback and cancellation.
- `core/workspace/lookup` — dictionary and image lookup sessions, requests and outcomes.
- `core/ocr` — OCR extraction, provider/language projection, workflow resolution, preprocessing
  and the OCR Workspace store. Capture routing belongs to `MainStore`; the workspace owns only
  OCR work and edited text.
- `core/document` — document translation and format-specific text/subtitle/DOCX/PDF ownership.
- `core/settings` — configuration, persistence, migration and the settings store.
- `core/plugin` — loading, assessment, trust, install and lifecycle transitions.
- `core/main/mvi` — the main store, state and intents. It routes actions and renders shared
  workspace state; it does not own translation lifetimes.

`MainStore` `dispatch()`es intents, applies state transforms and emits events. A translation
request is captured once (source, target, translator, rules, comparison ids, secondary intent,
history policy); the use case owns its lifetime, and the primary-result, comparison and
secondary-output writes all revalidate that ownership in the same short lock as the state write
they guard. Subsystem ownership locks are never acquired in opposite orders, so starting,
editing, cancelling and invalidating cannot deadlock. The guarantee is per request identity, not
a global lock: unrelated translators, comparisons and plugins still run concurrently.

### Use cases

Business logic lives in feature owners, not stores. Each one guards a narrow responsibility:

- `TranslateTextUseCase` — captures one request, runs it, owns cancellation/replacement and
  publication validity; coordinates comparison work and secondary output
- `TranslationCoordinator` — origin-based workflow policy: auto-copy, secondary refresh
  fallback, cancellation normalization, completion identity
- `SummarizeUseCase` / `RewriteUseCase` — typed `ExtraOutputResult` successes and failures
- `HandleTextToSpeechUseCase` — resolves language, calls TTS, plays audio
- `OcrExtractionUseCase` — resolves an OCR provider, applies the common timeout/failure mapping,
  optionally asks for structured geometry, and returns a typed result to the store or workspace
- `PerformSpellCheckUseCase` — calls spell checker, returns corrections
- `SelectActiveServiceUseCase` — observes available services, resolves language lists
- `CheckForUpdatesUseCase` — queries GitHub releases API
- `workspace/lookup` — `LookupWordUseCase`, `SearchImagesUseCase`,
  `FetchInlineDefinitionUseCase` and their session owners

### Plugin subsystem

`PluginManager` orchestrates:
- `PluginLoader` — scans the `plugins/` directory, validates manifests and assesses archives
  without executing them, so changed-known approval precedes executable loading
- `PluginRegistry` — thread-safe in-memory store of loaded plugins
- `PluginLifecycleHandler` — calls `initialize`, `onEnable`, `onDisable`, `shutdown`
- `PluginTransitionOwner` — serializes trust and lifecycle transitions per plugin id, so
  enable/disable/install/uninstall cannot interleave for one plugin while unrelated plugins
  still proceed independently. Shutdown settles every accepted transition concurrently against
  one global bound; a transition that outlives it owns its instance until its callback returns
  and finalizes it then, so teardown never overlaps a running lifecycle callback
- `PluginInstaller` — copies JARs, handles install/uninstall/verification
- `PluginSettingsManager` — reflects `@field:Setting`, `@SettingGroup`, and `@PluginAction` annotations to build the settings UI dynamically

### Plugin settings annotation system

Settings panels are generated entirely from annotations on the plugin's `PluginSettings.Configurable` subclass — no UI code required from plugin authors:

- `@field:Setting` — marks a `var` property as a settings field; declares label, type, order, validation, conditional visibility (`showIf`)
- `@SettingGroups` / `@SettingGroup` (class-level) — organize fields into collapsible named sections
- `@PluginAction` (method-level) — renders a button that invokes a `suspend fun(): String?` on the settings instance; result is shown as a message (useful for "Test Connection")
- `SettingType` — `TEXT`, `PASSWORD`, `TEXTAREA`, `BOOLEAN`, `DROPDOWN`, `NUMBER`, `SLIDER`, `FILE_PATH`

`DynamicPluginSettingsDialog` uses reflection to walk the settings class at runtime and build a `JPanel` from the discovered fields and actions.

### Settings

`SettingsRepository` persists configuration to DataStore and owns store construction, its
storage scope and file lifetime; writes return typed `SettingsError` results instead of being
swallowed. `SettingsStore` holds a working copy (draft) that can be edited without committing —
Apply saves it, Cancel discards it — and serializes accepted saves so shutdown can drain them.
The same repository exposes atomic plugin-state updates used by `PluginManager`.

`ActiveServiceManager` resolves "which service should I use?" from the current preset and live service registry.

`ConfigMigrator` handles version-to-version migration of the persisted `Configuration`. When the app loads a config file written by an older version, `ConfigMigrator` upgrades it field-by-field (adding new fields with defaults, renaming changed fields) before handing it to `SettingsRepository`. This ensures zero data loss when new settings are introduced.

### Localization

`LocalizationManager` loads TOML locale files from the `languages/` directory next to the JAR. It holds all string tables in `ConcurrentHashMap`s for thread-safe access. On language switch, it reloads the active locale table and emits an event that triggers a full UI re-render.

The English fallback is embedded in the JAR at `core/src/main/resources/localization/embedded_en.toml` — so the app always has strings even if the `languages/` folder is missing.

---

### Theme system

`ThemeManager` (in `:ui-swing`) manages all themes:

- **Built-in themes** — 31 themes (17 custom `.theme.json` files embedded in the JAR + 14 FlatLaf IntelliJ themes bundled at compile time). Always available regardless of the external `themes/` folder.
- **External themes** — scanned from `File(appDataDirectory, "themes")` at startup and on `reload()`. Any `.theme.json` file found there is loaded with `IntelliJTheme.createLaf()` and added to the available list. The `themes/` folder ships in the release zip alongside `languages/` and `plugins/`.
- **OS Default** — a sentinel theme ID (`"os_default"`) that resolves to the platform-preferred dark or light theme at apply time, using OS-specific detection (Windows registry, `defaults read` on macOS).
- **Platform defaults** — macOS → `mac_dark`/`mac_light`; Linux → `resharper_dark`/`resharper_light`; Windows → `flat_dark`/`flat_light`.
- **Animated transitions** — uses `FlatAnimatedLafChange.showSnapshot()` / `hideSnapshotWithAnimation()` to cross-fade between themes without a visible flash.

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

## Package organization

The tree reflects ownership rather than layer alone:

```
core/
  workspace/translation/   translation runs, comparisons, secondary output, workflow policy
  workspace/lookup/        dictionary/image lookup sessions and outcomes
  document/                document translation + text/DOCX/PDF behavior
  settings/                configuration, persistence, migration, settings store
  plugin/                  loader, assessment, trust, installer, lifecycle
  main/mvi/                MainStore, MainState, MainIntent and shared workspace state
  shared/                  logging, arch abstractions, notifications, small utilities
```

Move a responsibility only when its owner is already stable, one feature at a time. Keep `:api`
and plugin-facing packages compatible; do not add aliases to mask a move. UI packages mirror the
feature names where they render one (`ui-swing/main/lookup` and `ui-swing/settings`).

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
- `GLOBAL` — registered with the native input backend, fires system-wide even when QTranslate is not focused
- `LOCAL` — registered via Swing `InputMap`/`ActionMap`, fires only when QTranslate has focus

Global input runs on **QInput** (`ui-swing/.../main/input/QInputBackend.kt`), a native runtime accessed through the `GlobalInputBackend` interface. `MainGlobalKeyListener` owns the boundary between the two scopes and the backend: it derives the desired `GLOBAL` registration set from the current bindings, reconciles it against the backend through a `HotkeyRegistrationLedger` — so a registration the platform refuses to release stays non-dispatchable instead of silently firing — and exposes `LOCAL` bindings via `getLocalBindings()` for `MainAppFrame` to register on the `rootPane`.

`SHOW_MAIN_WINDOW` is always `GLOBAL` and special-cased: it fires on a double-Ctrl sequence tracked from raw key events by `DoubleCtrlDetector`, not a registered KeyStroke.

**Hotkey presets** (`HotkeyPresets.LEGACY`, `HotkeyPresets.MODERN`) are ready-made binding sets a user can switch to from Settings → Hotkeys. `HotkeyPresets.identify()` compares the current bindings against both and reports `HotkeyPresetKind.CUSTOM` the moment they diverge from either — there is no persisted "which preset am I on" flag, it is always derived from the bindings themselves.

---

## Notification system

`NotificationBus` is a `SharedFlow`-based pub/sub channel for cross-component notifications. `PluginManager` posts to it when plugins fail to load, need verification, or when no plugins are found at all. `MainAppFrame` subscribes and routes messages to the status bar via `StatusBarController.handleNotification()`.

This keeps `PluginManager` decoupled from the UI — it posts a domain notification, not a UI event.

---

## Plugin loading lifecycle

```
PluginLoader.assessDirectory() / assessFile()
  → copy the JAR into an app-owned read-only snapshot (no provider code runs)
  → read plugin.json and the services descriptor as plain ZIP entries
  → verify API version compatibility, then SHA-256 the snapshot
  → PluginRegistry.validateAndFilter (blank IDs, duplicate IDs)
  → fingerprint comparison against the last approved hash
      unchanged / first seen → PluginLoader.loadApproved(snapshot)
      changed                → PendingPlugin (AWAITING_VERIFICATION), nothing constructed
  → PluginRegistry.put(container)

PluginLifecycleHandler — every callback below is serialized per plugin by
PluginTransitionOwner, so the "lifecycle methods are called sequentially" contract holds
  → initialize(container)   ← Plugin.initialize(context)
  → enable(container)       ← Plugin.onEnable() + getServices()
  [user action]
  → disable(container)      ← Plugin.onDisable()
  → shutdown(container)     ← Plugin.onDisable() (if enabled) + Plugin.shutdown(),
                              closes the class loader and releases the snapshot

Shutdown closes the transition gate, settles accepted transitions, then finalizes each
plugin exactly once; later requests are rejected.
```

`loadApproved` is the only place provider code is constructed: it verifies the snapshot hash
again, defines the provider class from the snapshot's own code source, and only then calls the
constructor. A changed JAR therefore never executes before the user confirms it, and the
previous approved hash is preserved across restarts so the pause survives a relaunch.

Plugins whose JAR hash changed since the last run are held in `AWAITING_VERIFICATION` until the user confirms the change.