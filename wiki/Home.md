# QTranslate Wiki

Welcome to the QTranslate documentation wiki.

---

## For Users

- [Installing Plugins](Installing-Plugins.md) — how to find, install, and configure plugins
- [Adding a Language](Adding-a-Language.md) — translate the QTranslate interface into your language

## For Developers

- [Building from Source](Building-from-Source.md) — compile and run QTranslate locally
- [Architecture](Architecture.md) — how QTranslate is structured and why
- [Creating a Plugin](Creating-a-Plugin.md) — build your own translation engine, OCR, or TTS plugin
- [Contributing](Contributing.md) — how to contribute code, docs, or translations

### Plugin examples (in the repo)

The bundled plugins are the best reference for plugin development:

| | Source |
|--|--------|
| 🔵 | [`plugins/google-services/`](../plugins/google-services/src/main/kotlin) — Translator, TTS, OCR, Spell Checker, Dictionary with API key settings |
| 🟠 | [`plugins/bing-services/`](../plugins/bing-services/src/main/kotlin) — Translator, TTS, Spell Checker with token auth |
| 🔧 | [`plugins/common/`](../plugins/common/src/main/kotlin) — shared HTTP client, language mapper, JSON parser |

---

Can't find what you're looking for? [Open an issue](https://github.com/ahatem/qtranslate/issues/new) and ask.