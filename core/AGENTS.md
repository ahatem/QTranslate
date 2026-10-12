# `:core` — business logic and state

Use cases, stores, repositories, settings, plugin runtime, updater, and
document processing. UI-agnostic.

- No Swing **components**: never import or construct `JFrame`, `JDialog`,
  `JComponent` and friends, never use `SwingUtilities`/`SwingWorker`, and never
  add windows, dialogs, or presentation behavior. If you need one, the design is
  wrong — stop and replan.
- Non-widget AWT/Swing **value** types are fine and already precedented:
  `javax.swing.KeyStroke` and `java.awt.event.KeyEvent`/`InputEvent` for hotkey
  persistence (`settings/data/HotkeyBinding.kt`, `HotkeyPresets.kt`), and
  `java.awt.Font`, `RenderingHints`, `font.LineBreakMeasurer`, `font.TextLayout`,
  `font.TextAttribute`, `BufferedImage`, `Color`, `Rectangle` for document text
  and image measurement (`document/DocumentTranslation.kt`). Read the local
  precedent before adding more UI-framework coupling; `:core` depends on
  coroutines-swing only for the EDT-dispatched work it already owns.
- State flows through stores (`MainStore`, `SettingsStore`): intents in,
  `StateFlow` state out, one-shot occurrences through the event channel.
  UI observes; it never reaches into store internals.
- Expected failures use `com.github.michaelbull.result.Result` with the typed
  error (`ServiceError`, `SettingsError`, `UpdaterError`). Never throw for
  routine failures; never invent a parallel error convention.
- Blocking I/O always inside `withContext(Dispatchers.IO)`. CPU work on
  `Dispatchers.Default`. No `Thread.sleep`, no arbitrary delays to paper over
  races — use deterministic, event-driven mechanisms.
- Settings changes go through `SettingsRepository` + `ConfigMigrator`
  (see `qtranslate-settings-migration`). Writes return `Result`; corruption
  must recover from timestamped backups, never lose user data.
- Cross-cutting rules: notifications via `NotificationBus`; user-visible text
  via `LocalizationManager` (never hard-coded UI strings in logic); history
  through `HistoryRepository`; time comes from a monotonic clock, never wall
  time for durations.
- Test the quiet expensive mistakes first: migration/recovery, plugin
  load/validate, HTTP proxy/timeout/retry rules. Use-case coverage is thin —
  add tests for changed behavior, not for ceremony.
