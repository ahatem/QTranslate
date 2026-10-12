# `:api` — public plugin contract

Public plugin contracts, shared data and error types, and the
compatibility/versioning semantics those contracts require. Every third-party
plugin builds against this module, so compatibility matters more here than
anywhere else.

- Public interfaces and data types, plus pure contract-level logic such as the
  `ApiVersion` compatibility check and `LanguageCode` validation. No host
  application, service, or UI implementation; no I/O; no business logic; no
  Swing.
- Only approved dependencies (coroutines, result types). Check
  `api/build.gradle.kts` before adding any import outside `kotlinx`/`kotlin`.
- KDoc on every public interface, method, and data type.
- Evolve additively: new optional interfaces or defaulted members, never
  signature changes or removals without a major-version discussion.
- Adding a language means extending the shared `LanguageCode` catalog in its
  natural position — never an inline provider alias. Display names come from
  the existing locale mechanism, not hard-coded strings.
- Expected failures are typed (`ServiceError` etc.) and returned as `Err`,
  never thrown. `retryable` flags must be honest.
- `@Setting` fields need the Java-reflection placement the settings codegen
  expects (`@field:`); keep `SettingType` mappings exact.
