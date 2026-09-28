# QTranslate Wiki

Welcome to the QTranslate documentation wiki.

---

## For Users

- [Installing Plugins](Installing-Plugins.md) — how to find, install, and configure plugins
- [Hotkeys](Hotkeys.md) — global vs. app-local hotkeys, switching presets, and troubleshooting one that won't fire
- [System Services](System-Services.md) — the bundled offline OCR/TTS/spell-check plugin, and what each platform needs
- [AI Services](AI-Services.md) — point translation, summarizing, rewriting, and Vision OCR at a local Ollama/LM Studio server or a cloud OpenAI-compatible endpoint
- [Adding a Language](Adding-a-Language.md) — translate the QTranslate interface into your language
- [Adding a Theme](Adding-a-Theme.md) — install community themes or create your own

### Where QTranslate stores things

Settings, plugin data, and logs all live in one app data directory next to nothing else — QTranslate
never writes outside it. The exact path is printed in the log at startup (`App data directory: ...`)
and is also where log files themselves are kept. See [Adding a Language](Adding-a-Language.md#quick-start)
or [Adding a Theme](Adding-a-Theme.md) for how to find and use that folder.

## For Developers

- [Building from Source](Building-from-Source.md) — compile and run QTranslate locally
- [Architecture](Architecture.md) — how QTranslate is structured and why
- [Creating a Plugin](Creating-a-Plugin.md) — build your own translation engine, OCR, or TTS plugin
- [Contributing](Contributing.md) — how to contribute code, docs, or translations
- [Releasing](Releasing.md) — test, package, publish, and verify an official release

### Plugin Marketplace _(coming soon)_

The signed in-app catalog and independent plugin updater are planned but are not available yet. Today, users install trusted plugin JARs manually through **Settings → Plugins**.

Plugin authors can prepare for future catalog support by publishing releases, checksums, compatibility metadata, and the `qtranslate-plugin` topic.

→ [Current plugin publishing guide](Creating-a-Plugin.md#publishing-on-github)

### Plugin examples (in the repo)

The bundled plugins are the best reference for plugin development:

| | Source |
|--|--------|
| 🔵 | [`plugins/google-services/`](../plugins/google-services/src/main/kotlin) — Translator, TTS, OCR, Spell Checker, Dictionary with API key settings |
| 🟠 | [`plugins/bing-services/`](../plugins/bing-services/src/main/kotlin) — Translator, TTS, Spell Checker with token auth |
| 🤖 | [`plugins/ai-services/`](../plugins/ai-services/src/main/kotlin) — Translator, Summarizer, Rewriter, Spell Checker, Dictionary, Vision OCR via OpenRouter or a local endpoint |
| 💻 | [`plugins/system-services/`](../plugins/system-services/src/main/kotlin) — offline OCR, TTS, and Spell Checker with no settings at all, backed by a different implementation per platform |
| 🌐 | [`plugins/mozhi-services/`](../plugins/mozhi-services/src/main/kotlin), [`plugins/libretranslate-services/`](../plugins/libretranslate-services/src/main/kotlin) — Translator against a user-supplied self-hosted instance URL |
| 📄 | [`plugins/csv-services/`](../plugins/csv-services/src/main/kotlin) — Dictionary backed by a local file, no network at all |
| 🔧 | [`plugins/common/`](../plugins/common/src/main/kotlin) — shared HTTP client, language mapper, JSON parser |

---

Can't find what you're looking for? [Open an issue](https://github.com/ahatem/qtranslate/issues/new) and ask.
