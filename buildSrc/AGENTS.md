# `buildSrc/` — build conventions and release guards

Owns the Kotlin/JVM convention plugin and all release-guard task logic
(version, class-version ceiling, size budgets, metadata, checksums, artifact
verification). Changes here affect every module and the release pipeline.

- `kotlin-jvm.gradle.kts` sets toolchain 21 with `jvmTarget=11` (keeps `:api`
  usable by older runtimes) and `useJUnitPlatform()`. Do not change targets
  or toolchains without a repo-wide reason — the Java 17 bytecode ceiling
  (major 61, enforced by `ValidateReleaseClassVersionsTask`) is load-bearing
  for the portable ZIP and plugin JARs.
- Release tasks are append-only in spirit: `Validate*` tasks assert, `Generate*`
  tasks produce (`release-metadata.json`, `SHA256SUMS.txt`, `SIZE_REPORT.md`,
  `portable-plugin-ids.txt`). Never loosen an assertion to unblock a release.
- `ReleaseClassVersionVerifier` has its own unit tests (`-p buildSrc test`) —
  update code and tests together.
- Size budgets (app/portable/plugins) are product decisions; changing one
  needs maintainer approval, not an agent judgment call.
- Anything here that alters emitted artifacts requires re-running the matching
  `validate*`/`verifyReleaseArtifacts` tasks locally before pushing.
