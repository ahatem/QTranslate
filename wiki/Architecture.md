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

**Dependency rule:** lower layers never import from upper layers. `:core` has no Swing imports. `:api` has no Coroutines imports (except what it needs for its own interfaces). Plugins only import `:api`.

---

## :api — the plugin contract

Everything a plugin author needs lives here: service interfaces (`Translator`, `TextToSpeech`, `OCR`, `SpellChecker`, `Dictionary`), the `Plugin` base class, `PluginContext`, `PluginSettings`, request/response data classes, and `ServiceError`.

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

- `TranslateTextUseCase` — calls the active translator, manages history
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
