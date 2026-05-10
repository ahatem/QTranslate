# Changelog

All notable changes to QTranslate are documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versions follow [Semantic Versioning](https://semver.org/).

---

## [Unreleased]

### Added
- **Quick Dictionary** — global `Ctrl+D` hotkey, floating panel with auto-lookup on selected text
- **Translation history** dialog — view, restore, and clear past translations
- **AdvancedTextPane** — hint text, character count overlay, and theme-safe wavy spell-check underlines in the input area
- **Clickable ERROR/WARNING status chip** — opens `ErrorDetailPopup` with scrollable full-message and Copy button
- **Output panel hint text** guiding new users (all 13 locales)
- **Markdown-rendered release notes** in the update dialog with "View on GitHub" button
- **Quick Translate popup overhaul** — configurable idle timers, interruptible fades, 120 ms mouse-exit debounce, screen-bounds hit test
- **Customizable SHOW_MAIN_WINDOW hotkey** with independent Double-Ctrl toggle option
- **AI Services plugin v2.0.0** — OpenRouter-first single endpoint, 300+ models via one API key, AI Dictionary service, AI Vision OCR service, HTTP 402 error handling
- **Plugin settings annotation system v2** — `@SettingGroup`/`@SettingGroups`, `@PluginAction`, `SLIDER` type, `showIf` conditional visibility, inline field validation, character counters
- **Stop TTS playback mid-audio** — Listen button becomes Stop while audio is playing
- **OCR snap dialog action bar** — Copy Text, Copy Image, Save Image, Re-crop buttons, and selection dimensions overlay
- **About dialog redesign** with updated team and contact links
- **In-app donation nudge** — Support button in About dialog; one-time milestone notification at 500 translations
- `FUNDING.yml` — GitHub Sponsors and Buy Me a Coffee links on the repository sidebar
- **ThemeManager overhaul** — platform-aware defaults (macOS → mac dark/light, Linux → ReSharper dark/light, Windows → flat dark/light), OS dark/light mode detection, animated cross-fade transitions
- **"OS Default" theme option** in Appearance settings — follows system dark/light preference at apply time
- **Settings dialog overhaul** — two-column Services grid, keyboard shortcut chip badges, text wrapping throughout, PluginsPanel with chip-wrapped service list and always-visible action bar
- **Structured notification system** — status bar chips, in-app update dialog, `StatusCode`-based localized status messages
- Streaming TTS audio URL support — long audio is played while downloading rather than waiting for the full file
- Instant translate improvements — output clears on blank input; minimum-character guard prevents spurious requests
- All 13 language files synced with the latest `embedded_en.toml` structure; missing keys translated

### Fixed
- Thin indeterminate progress bar in status bar during translation (replaces the old circular spinner)
- Output panels are now read-only with a "Set as Input" context menu — translation results can't be accidentally edited
- Input area stays editable while a translation is in progress — no more blocked/greyed state
- Configurable translate keyboard shortcut (was hardcoded to `Enter`)
- About dialog links correctly wired: "How to Use" → GitHub wiki, "Contact Us" → new GitHub issue
- DictionaryResultView no longer flickers on settings save or language switch
- In-flight translation cancelled immediately when new input arrives
- Settings action wired correctly in system tray menu
- Localization thread safety — string caches upgraded to `ConcurrentHashMap`
- MainStore input race condition — state captured atomically before update
- Hotkey double-registration race — guarded with `AtomicBoolean.compareAndSet`
- Hardcoded English strings in Quick Translate popup and Preset Selector replaced with localized equivalents
- Config deserialization safety — all `Configuration` fields have defaults; `ConfigMigrator` upgrades old config files without data loss
- `NotificationBus` replay guard — stale notifications (> 5 s old) are discarded on new subscriber to prevent duplicate chips after UI rebuild
- Popup multi-monitor position — `GraphicsConfiguration` derived from current mouse position, not the dialog's current screen
- Window panel transparency minimum guard (20 %) and idle-timeout minimum (2 s)
- TOML localization quoting — all non-English language files now correctly escape `\"` inside dictionary not-found messages

---

## [1.1.0] — 2025-01-10

### Added
- Drag-and-drop and `Ctrl+V` clipboard image paste directly into the OCR area for translation
- Translation rules for automatic target language selection — Auto mode now picks the right language more reliably
- Auto-detect OS language on first launch — interface defaults to your system language
- Persist main window size and position across restarts
- Bengali (bn-BD) translation
- Italian translation
- Hungarian translation

### Fixed
- "Auto-Detect" label and "Default" preset name now fully localized; close dialog redesigned
- Escape sequences in TOML localization strings now parsed correctly
- Scroll speed in settings dialog panels improved
- App now fully awaits plugin loading before showing the main window — no more flash of empty state on startup
- Italian language processing ambiguity in the Bing plugin resolved
- Concurrent `DataStore` creation race condition in `PluginKeyValueStore` fixed
- Multiple app instance prevention via socket lock

---

## [1.0.0] — 2024-11-15

### Added
- Initial public release
- Global `Ctrl+Q` hotkey → floating Quick Translate popup with auto-translation
- Main window with instant translation (translates as you type), output panels, and extra-output panel for backward translation, summarize, and rewrite
- Screen OCR — draw a rectangle anywhere on screen to extract and translate text
- Spell checking with live wavy underlines and click-to-apply suggestions
- Global hotkey system — every action is bindable, configurable as global or app-local
- Plugin system — install `.jar` plugins at runtime; no restart required
- Google Services plugin — Translator, Text-to-Speech, OCR, Spell Checker, Dictionary
- Bing Services plugin — Translator, Text-to-Speech, Spell Checker
- 30+ bundled themes via FlatLaf with animated light/dark transitions
- RTL layout support for Arabic, Hebrew, Farsi, and more
- Three window layouts: Classic, Side-by-side, Compact
- Localization in 10 languages: Arabic, Chinese, English, French, German, Japanese, Portuguese, Russian, Spanish, Turkish
- Portable mode — all app data lives next to the JAR; works from any folder

---

[Unreleased]: https://github.com/ahatem/QTranslate/compare/v1.1.0...HEAD
[1.1.0]: https://github.com/ahatem/QTranslate/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/ahatem/QTranslate/releases/tag/v1.0.0
