# `:plugins/*` — service implementations

Each plugin is independently packaged and installed at runtime. See
`qtranslate-plugin-provider` for the full workflow.

- Depend on `:api` and `:plugins:common` only. Never on `:core`, `:ui-swing`,
  or another service plugin. Shared HTTP/JSON/retry/mapping helpers live in
  `:plugins:common` — extend those instead of forking per-plugin copies.
- Every service declares `key/name/version/supportedLanguages` honestly;
  `plugin.json` (`id`, `version`, `minApiVersion`) plus the `Plugin` service
  registration must stay consistent or install/validation fails.
- Language mapping is per-provider-contract: out-bound request codes and
  in-bound detection semantics change independently — never mirror one as the
  other without provider evidence. Unsupported input fails explicitly
  (`ServiceError.UnsupportedLanguageError`), never silently maps to a nearby
  value.
- HTTP via the shared client: honor proxy/timeout/retry rules, keep batch
  behavior, surface stable `ServiceError`s. No per-plugin TLS weakening.
- Privacy: never log request/response bodies, keys, or user text — metadata
  only. Sanitize provider errors before surfacing.
- Tests use recorded/stubbed responses (`FakePluginContext`, `TextHttpClient`
  from `:plugins:common`) — never live production calls. Assert the generic
  mapping contract in the existing test file for that concern; add a
  special-case branch only when generic behavior is genuinely wrong.
