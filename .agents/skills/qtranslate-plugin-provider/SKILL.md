---
name: qtranslate-plugin-provider
description: Develop or fix a QTranslate translation/dictionary/TTS/OCR provider plugin with correct boundaries, language mapping, HTTP behavior, and recorded-response tests. Use for any plugins/* change.
---

# QTranslate Plugin Provider

## Rules

- Depend on `:api` + `:plugins:common` only. Reuse the shared HTTP client,
  JSON parsing, retry, and `LanguageMapper` helpers — do not fork copies.
- Declare `key/name/version/supportedLanguages` honestly; keep `plugin.json`
  (`id`, `version`, `minApiVersion`) and the `Plugin` service registration
  consistent.
- Language mapping follows the provider's published contract: outbound
  request codes and inbound detection semantics evolve independently. Fetch
  the authoritative source (provider docs, CLDR/BCP-47 where relevant) and
  cite it. Unsupported input → explicit `ServiceError.UnsupportedLanguageError`,
  never a silent nearby mapping. New shared codes go in the `LanguageCode`
  catalog, never inline aliases.
- HTTP: honor proxy/timeout/retry rules from `:plugins:common`; keep batch
  behavior; return stable `ServiceError`s; never weaken TLS.
- Privacy: never log request/response bodies, API keys, or user text.
  Sanitize provider errors before surfacing them.
- Tests use recorded/stubbed responses (`FakePluginContext`,
  `TextHttpClient`) — never live production calls. Cover parsing, language
  mapping, and error paths in the existing test file for that concern.

## Guide

Full contributor guide: `wiki/Creating-a-Plugin.md`.
