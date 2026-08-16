# Changelog

All notable changes to QTranslate are documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versions follow [Semantic Versioning](https://semver.org/).

---

## [Unreleased]

### Added
- **Image search for a selected term.** Select a word and press `Ctrl+Shift+Q`, or use the context menu, the main menu or the tray menu, to see pictures of it from Wikimedia Commons. Built for reading technical material, where a diagram explains a term faster than a definition does. The grid fits itself to the popup, clicking a picture enlarges it in place rather than opening a browser, and Escape steps back out of the picture before it closes the search. A **media type** filter picks between Anything, Photographs and Diagrams, since which one helps depends on what you are reading. The licence stays visible under each picture, with the full credit on the source page a click away
- **CSV Dictionary plugin.** Point it at your own CSV file and look terms up in it: a glossary, an abbreviation list, a table of error codes, a set of study mnemonics. The term column, the definition column, the delimiter and the label shown beside each result are all configurable, so the file does not have to be arranged any particular way
- **A short definition under single-word translations.** Translate a single word and a one-line definition appears beneath it, in the popup and the main window alike, with no click and no toggle. It is kept out of the output pane on purpose, so copying a translated word still copies just the word. Longer translations lay out exactly as before
- **Listen on dictionary results.** A speaker beside the headword reads it aloud, in the quick popup, the standalone dialog and the docked panel. It turns into a stop button while audio is playing
- **Test connection** in plugin settings, for plugins that need credentials. It tests the values on screen rather than the ones saved last time, and says whether a key was refused or was never filled in
- **Plugins can ship their own translations**, as TOML bundles inside the plugin JAR, so plugin text is no longer English-only
- **Documents can be pasted** as well as dropped, and dragging a file over the window now draws an overlay showing it will be accepted
- **Click outside to close** is now a setting for the floating popups, on by default. Turning it off suits translating a word and then working in the document beside it. Pinning still overrides it
- **Search the settings.** A field above the sidebar finds any setting by name, by the section it sits in, or by a word that appears only in its explanation. Typing "fades" reaches the popup idle timeout. Choosing a result opens its page, scrolls to the setting and marks it
- **Default source language**, alongside the existing default target. A default for one side and not the other was an arbitrary half
- **Select All and Clear** in the text pane context menu. Clear can be undone
- **Listen on the dictionary** now also reads the headword in the language it was looked up in, rather than guessing from the panel it came from
- **A local, private AI setup.** Point AI Services at [Ollama](https://ollama.com) or LM Studio and nothing leaves your machine: no account, no API key, no per-word cost. For work under an NDA, proprietary code, or anything else that cannot go to a cloud service. [Setup guide](wiki/AI-Services.md)
- **Change languages from the Quick Translate popup.** The language pair was static text, so retargeting a translation meant opening the main window — a long way round for the commonest follow-up there is: yes, but into French. Source and target are now pickers with a swap between them, driving the same intents the main window uses, so the two stay in step and the choice is remembered the same way. Auto is offered as a source and not as a target, where it would mean nothing
- **The popup can be driven from the keyboard**, without reaching for the mouse to change a language

### Changed
- ⚠️ **Plugin API v2. Third-party plugins built against v1 are refused at load and must be rebuilt.** There are no known third-party plugins, so in practice this affects nobody today, but it breaks a documented contract and is stated plainly for that reason. All bundled plugins are migrated, and existing service selections are converted automatically on first launch.

  What changed: services declare what they can do instead of the host guessing from the interfaces they implement, so one service can be offered as both a translator and a dictionary. Summary lengths and rewrite styles are now data a plugin defines rather than a fixed list the app owns. Settings are typed and scoped per plugin instance, credentials live in their own store, and a service can offer a cheap configuration check
- **The interface is smaller at zoom levels above 100%, and that is the bug being fixed.** With the UI scale set to 125%, every theme or font change used to enlarge the whole interface by a further 25%, compounding each time, so it had drifted well past its intended size. It now stays put. If the app looks smaller than you remember at the same zoom setting, this is why; raise the scale in Settings → Appearance if you prefer the larger size
- **The Settings dialog is reorganised.** Two groups, Translation and Interface, rather than eight flat pages in no particular order. Window & Layout is split into Layout and Popups. Four settings moved to where people look for them, and the three floating popups now offer the same controls as one another
- **The document translation dialog matches the rest of the app.** Its fields and buttons were pinned to a fixed height taller than every equivalent control elsewhere, and ignored the UI font setting entirely
- API keys move out of the plugin settings file into a separate credential store
- Popup auto-hide defaults rise from 3 seconds to 12 for translations, and from 8 to 20 for definitions, neither of which was long enough to read. Anyone who chose their own timeout keeps it
- Popup width is now bounded by a comfortable line length rather than only by a fraction of the screen, so a sentence no longer stretches across half a wide monitor
- Pressing a popup's hotkey while it is already open refreshes it in place instead of hiding it
- A popup keeps the result you were reading while it fetches the next one, showing a thin progress bar under its header instead of emptying itself
- The dictionary popup no longer opens by itself whenever a single word is translated. A dictionary you already have open still follows along
- README screenshots are recaptured in the bronze palette the app actually ships, at full display scale, and now include the side-by-side layout and document translation, two features the page described in prose but never showed. A gallery of thirty-four captures sits behind them, covering both themes across all three layouts and every settings page

### Fixed
- **The popup's language pickers no longer crowd out the buttons beside them.** They took the width of the longest language name, which on a narrow popup left them clipped. The closed control now shows the language code and the drop-down keeps full names, matching the translator picker beside it. With Auto resolved it shows the detected language's code rather than the word Auto, which says more in four characters; the full name is in the tooltip. The main window has room for real names and keeps them
- **Six labels showed their own key names instead of text.** The action button on the no-service message in all thirteen languages, and five of the six keyboard shortcuts added in 1.3.0 (clear input, swap languages, open settings, show history, translate document) read as `settings_hotkeys.action_clear_input` and the like. The strings had been written but appended without a line break, so the parser kept the first and discarded the rest of the line
- **Twelve languages were missing 89 strings each**, covering the plugin manager, document translation, image search and the status bar, and showed English in the middle of otherwise translated screens. All are now translated
- **Settings are never silently reset again.** A configuration that could not be read fell back to defaults with only a line in a log: the app looked freshly installed, and setting it up again overwrote the file that still held the original. The unreadable configuration is now copied aside, timestamped, and the failure is reported when the app starts
- **QTranslate now renders at the display's scale.** On a 150% or 200% display it drew scaled icons and borders around unscaled text, paddings, table rows and windows. The window opened at roughly half the size its own contents needed, clipping the language selectors to "Eng…" and "Ara…" and leaving the output pane no usable height; the history table clipped its headers to "Date …", "Lan…" and "Servi…"; and the docked dictionary was pinned to half its intended width, wrapping definitions one word per line. On an unscaled display nothing changes
- **Hotkeys no longer disturb the main window.** Summoning a popup could pull the main window out of the tray, push it away, or produce no popup at all, depending on the state the window had been left in
- Pressing Listen in a popup while speech was playing started a second playback with no way to stop either. It now stops, matching the main window
- A document dropped on a text pane did nothing. Document drop worked only on window chrome, which is the part of the window nobody aims at. Every part of the window now accepts the same things
- Clicking outside a popup did not close it. The old implementation could only see clicks landing on QTranslate itself, never the ones in the document being read
- Closing a pinned popup left it pinned, so the next one opened pinned and then auto-hid anyway
- Every dialog showed Java's default coffee cup icon instead of the application's
- The loading indicator never appeared, having been suppressed in exactly the case it was written for
- Opening Settings left an entire dialog in memory permanently, every time, and later theme changes ran the handlers of all the dead ones
- None of the floating popups survived a theme change; they went on painting the previous theme's borders and dimmed text for the rest of the session
- **The Arabic interface laid its panes out upside down.** With the interface language set to Arabic the output pane sat above the input and the extra pane above both, the docked dictionary could not be resized by dragging its divider, and the rule dividing the dictionary from the translation was drawn on the outer edge so the two areas ran together. The dictionary also opened at its minimum width rather than the size it asks for
- Switching themes drew a frame around components that are deliberately borderless: the service selector, the dictionary panel, the plugin lists and the settings content area
- The CSV plugin icon was drawn in black and disappeared into the dark theme, and the plugins screen ran its list straight into the detail pane with no divider
- Double-tapping Ctrl misfired on ordinary shortcuts, since every hotkey here is a Ctrl combination and two in quick succession looked identical to a deliberate double tap
- Image tile captions clipped mid-word instead of eliding, and the credit under each picture is now the licence alone, with the full credit in the tooltip
- The inline definition takes its text direction from its own script, so an Arabic definition sits under a right-aligned Arabic translation rather than opposite it
- **Mixed-direction text is aligned per paragraph.** A translation holding an Arabic paragraph and an English one used to take a single alignment for both, and the wrong one for half of it
- Line and paragraph spacing in the text panes opened up from Swing's default, which sets lines directly against one another and is tiring to read a paragraph in
- Vision OCR refused to run against a local AI endpoint, reporting a missing API key that a local server does not need
- Focusing a field in the document dialog showed a focus ring clipped along the edge of the panel holding it

### Performance
- Font fallback scanned the remaining text again for every font run, making a document with many scripts quadratic in allocation
- The enlarged image was rescaled with AWT's slowest scaler on the event thread on every resize event, continuously while dragging the popup's edge
- Thumbnails from an abandoned image search were fetched to completion, so four terms typed quickly downloaded four full grids with the wanted one last
- The text pane allocated a font, a string and an insets object on every caret blink
- **Right-to-left text lays out far faster.** Setting the direction rewrote attributes across every character in the document and forced a full re-layout; it now touches each paragraph once and skips those already correct. Laying out a large Arabic document falls from roughly 200ms to about 1ms, and a page-sized one from 40ms to nothing measurable

---

## [1.3.0] — 2026-08-14

### Added
- **QTranslate Light and QTranslate Dark** — purpose-built default themes built on the project's own palette: warm parchment and gold in the light theme, near-black and gold in the dark one, with OS light/dark synchronization and every colour verified against WCAG contrast targets
- **Format-preserving document translation** for DOCX, PDF, TXT, SRT, and VTT, including progress, cancellation, DOCX structure preservation, subtitle timing preservation, and best-effort PDF appearance mode
- **Modern plugin manager** — searchable and filterable installed-plugin list, inline enable/configure actions, drag-and-drop installation, detailed metadata and service lists, failure states, and complete plugin/service icon coverage
- **Configurable service selectors** — classic and enhanced layouts, icons/text display modes, overflow handling, automatic scrolling, and quick access to plugin configuration
- **Selection translate button** — optional floating button that appears next to text selected by dragging in any application; click it to translate without reaching for a hotkey (off by default, enable under Settings → General → Text Selection)
- **New translation plugins** — DeepL, Mozhi, MyMemory, LibreTranslate Local, Reverso, and Yandex Web
- **Wikimedia Reference plugin** with separate Wikipedia and Wiktionary dictionary services using official MediaWiki APIs
- **Batch translation and bilingual dictionary plugin API capabilities** for efficient document translation and contextual dictionary results
- **Release variants** — app-only JAR, portable ZIP, independently versioned plugin JARs, machine-readable metadata, SHA-256 checksums, and size reports
- **Portable Windows x64 package** with `QTranslate.exe` and a bundled trimmed Java runtime
- **Document translation button** in the history bar, and dropping a DOCX, PDF, TXT, SRT or VTT file on the window opens the translation dialog with it already selected
- **Six new keyboard shortcuts** — copy translation, clear input, swap languages, open settings, show history and translate document, all rebindable under Settings → Keyboard & Hotkeys
- **Guidance when no translation service is configured**, with a direct link to the Services settings, instead of a window that silently does nothing
- `runWithPlugins` manual QA launcher and `smokeTestAllPlugins` automated plugin lifecycle/service check

### Changed
- A single portable distribution bundles every included plugin, replacing the separate minimal and full builds that differed by less than 1 MB
- DeepL automatically uses the official API when a key is configured and otherwise uses its rate-limited free web fallback
- Mozhi settings provide known public instances, endpoint tests, and automatic fastest-instance selection
- OpenRouter defaults and setup guidance are clearer for first-time AI Services users
- Release archives are size-budgeted to keep portable downloads practical
- Copying a translation now confirms in the status bar instead of giving no feedback at all

### Performance
- **The translation appears as soon as it arrives.** With extra output enabled it was held back until the second request finished, so every translation felt as slow as the slower of the pair; the extra panel now loads on its own
- **The window opens before plugins finish loading.** Startup previously waited for every plugin to initialise — including ones that make network calls — leaving nothing on screen in the meantime
- Instant translation responds after 350 ms instead of 700 ms, so the result still reads as a response to what was typed

### Fixed
- **Every network service failed in the Windows package.** The bundled Java runtime was missing its elliptic-curve crypto provider, so every HTTPS connection failed and translation, dictionary and TTS all appeared broken
- Selected-text capture is more reliable across desktop applications
- Quick Translate now reads the original text aloud instead of the translation, so Listen can be used to check pronunciation of the word you selected
- Global hotkeys support non-US keyboard layouts
- Google translation supports Croatian and Farsi
- Quick Translate closes when clicking outside the popup
- Service types can be disabled independently
- Java2D selects the appropriate rendering pipeline instead of forcing one globally
- A malformed plugin is represented as a failure in the plugin manager without preventing app startup
- Non-English interface languages format numbers and dates correctly in the Windows package, which was missing its locale data
- The error detail popup and Quick Translate close buttons have tooltips

---

## [1.2.1] — 2026-05-11

### Added
- **Grouped theme selector** — Appearance settings now organises themes into Light, Dark, and Installed sections with bold section headers; a new "Sync with OS" checkbox replaces the flat "OS Default" entry and disables the dropdown when checked
- **Hotkey recorder redesign** — IntelliJ-style dialog: left-aligned action label above a plain text field with an embedded `+` button for selecting special keys (Enter, Escape, Tab, F1–F12, arrow keys, etc.) that cannot be typed directly; global hotkeys are paused while the recorder is open to prevent accidental triggers
- **Alt+1 / Alt+2 / Alt+3 focus shortcuts** — configurable in Settings → Keyboard & Hotkeys; work in all layouts including Compact (tabbed) mode
- **Compact layout tab tooltips** — each tab shows its keyboard shortcut in the tooltip

### Fixed
- Target language is now remembered across sessions — no longer resets to Arabic on every launch (#92)
- Tab key in the input pane now moves focus instead of inserting a literal tab character; Ctrl+Tab inserts a tab
- Caret is always visible in read-only output panes regardless of theme
- `Ctrl+C` / `Ctrl+X` and text drag-out reliably work in the input pane
- Translator service order in the selector is now stable across JVM restarts
- Key chip text in the Hotkeys table now renders with proper LCD/ClearType antialiasing
- Combo box height in Appearance settings is consistent with all other combo boxes on the page

### Changed
- All 12 non-English locale files fully translated — 20 previously English-only keys now localised (hotkey recorder strings, focus action names, default language settings, compact layout tooltips, theme group headers)

---

## [1.2.0] — 2026-05-10

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

[Unreleased]: https://github.com/ahatem/QTranslate/compare/v1.3.0...HEAD
[1.3.0]: https://github.com/ahatem/QTranslate/compare/v1.2.1...v1.3.0
[1.2.1]: https://github.com/ahatem/QTranslate/compare/v1.2.0...v1.2.1
[1.2.0]: https://github.com/ahatem/QTranslate/compare/v1.1.0...v1.2.0
[1.1.0]: https://github.com/ahatem/QTranslate/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/ahatem/QTranslate/releases/tag/v1.0.0
