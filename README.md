# QTranslate

<div align="center">

**A fast, extensible desktop translation suite for Windows, macOS, and Linux.**

[![Release](https://img.shields.io/github/v/release/ahatem/qtranslate?style=flat-square&color=4A90D9)](https://github.com/ahatem/qtranslate/releases/latest)
[![License](https://img.shields.io/github/license/ahatem/qtranslate?style=flat-square)](LICENSE)
[![Build](https://img.shields.io/github/actions/workflow/status/ahatem/qtranslate/ci.yml?branch=develop&style=flat-square&label=build)](https://github.com/ahatem/qtranslate/actions)
[![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen?style=flat-square)](CONTRIBUTING.md)

[Download](#-installation) · [Plugin Docs](wiki/Creating-a-Plugin.md) · [Contribute](CONTRIBUTING.md) · [Wiki](wiki/Home.md)

</div>

---

## What is QTranslate?

QTranslate lets you translate text, recognise text in images (OCR), listen to text-to-speech, and check spelling — all from a single keyboard shortcut, without opening a browser.

It is **plugin-driven**: translation engines, OCR providers, and TTS services are separate JARs you install at runtime. Don't like the default engine? Swap it. Want to add a private API? Write a plugin in an afternoon.

---

## ✨ Features

- **Instant translation** — translate as you type, with debounce
- **Quick translate popup** — select text anywhere, press `Ctrl+Q`, done
- **OCR** — capture a screen region and translate the text inside it
- **Text-to-speech** — listen to input or output in the detected language
- **Spell checking** — underline mistakes as you type
- **Translation history** — undo/redo through past translations
- **Multiple presets** — configure different service combinations and switch between them
- **Plugin system** — install, configure, enable, and disable service plugins at runtime
- **Themes** — FlatLaf-powered dark and light themes with custom accent colours
- **RTL support** — automatic layout mirroring for Arabic, Hebrew, and other RTL languages
- **Portable** — runs from any folder, all data lives next to the JAR

---

## 📦 Installation

### Option 1 — Download a release (recommended)

1. Go to [Releases](https://github.com/ahatem/qtranslate/releases/latest)
2. Download `QTranslate-<version>.zip`
3. Unzip anywhere you like
4. Run `QTranslate.jar` (double-click or `java -jar QTranslate.jar`)

> **Requirements:** Java 11 or later. [Download Java](https://adoptium.net)

### Option 2 — Build from source

See [Building from Source](wiki/Building-from-Source.md).

---

## 🔌 Installing Plugins

Plugins add new translation engines, OCR providers, or TTS services.

1. Open **Settings → Plugins**
2. Click **Install Plugin…**
3. Select the plugin `.jar` file
4. Enable the plugin and configure it if needed (API keys etc.)
5. Go to **Settings → Services & Presets** and assign the new service to your preset

For more detail see [Installing Plugins](wiki/Installing-Plugins.md).

---

## 🌍 Adding a Language (UI Translation)

QTranslate's interface can be translated into any language using a simple TOML file.

1. Open your QTranslate data folder (shown at startup in the logs)
2. Navigate to the `languages/` subfolder
3. Copy `en.toml` and rename it to your language code (e.g. `ar.toml`, `fr.toml`)
4. Translate the values — the keys must stay in English
5. Go to **Settings → Appearance** and select your language

Want to share your translation? See [Adding a Language](wiki/Adding-a-Language.md).

---

## 🧩 Community Plugins

> **Built a plugin?** If you've created a QTranslate plugin and want to share it with the community, [open a Plugin Submission issue](https://github.com/ahatem/qtranslate/issues/new?template=plugin_submission.md). Quality plugins get listed here and linked from the wiki — it's a great way to get your work in front of users.

| Plugin | Type | Author | Description |
|--------|------|--------|-------------|
| *(be the first!)* | — | — | — |

---

## 🏗️ Building a Plugin

The plugin API is designed to be simple. A minimal translator plugin is about 50 lines of Kotlin.

**Full guide:** [Creating a Plugin](wiki/Creating-a-Plugin.md)

```kotlin
class MyPlugin : Plugin<PluginSettings.None> {
    override val id      = "com.example.my-plugin"
    override val name    = "My Plugin"
    override val version = "1.0.0"

    override fun getSettings() = PluginSettings.None
    override fun getServices() = listOf(MyTranslatorService())
}
```

---

## 🤝 Contributing

Contributions are very welcome — bug fixes, new features, translations, documentation, and plugins. Read [CONTRIBUTING.md](CONTRIBUTING.md) before opening a PR.

---

## 📄 License

QTranslate is released under the [MIT License](LICENSE).

---

<div align="center">
<sub>Built with Kotlin · FlatLaf · Coroutines · Love</sub>
</div>