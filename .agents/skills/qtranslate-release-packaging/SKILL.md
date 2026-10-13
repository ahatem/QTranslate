---
name: qtranslate-release-packaging
description: Release and packaging workflow for QTranslate covering version alignment, portable builds, Windows MSI, metadata, checksums, and release CI. Use for any buildSrc, packaging, or release change.
---

# QTranslate Release Packaging

R3. Never run or alter release behavior casually.

## Checklist

- Version alignment: tag == `AppConstants` (`validateReleaseVersion`); betas
  via prerelease tags first to exercise the pipeline.
- Artifacts: portable ZIP (with `portable.flag`, plugin inventory, languages,
  themes, icons), app-only JAR, per-plugin JARs, `release-metadata.json`,
  `SIZE_REPORT.md` (budgets enforced), `SHA256SUMS.txt`.
- Windows: app-image + MSI via packaging scripts (Java 21 + WiX); MSI is
  internal-only until signed — never publish an unsigned MSI.
- Install/upgrade/uninstall must preserve user data byte-for-byte
  (verifier scripts assert this — never loosen them).
- Java 17 bytecode ceiling (major 61) holds for shipped artifacts despite the
  21 toolchain; macOS helpers arrive via `-PmacVisionHelper`/`-PmacSpellHelper`.
- Class-version, size, and inventory gates (`validate*`,
  `verifyReleaseArtifacts`) run before anything ships.

## Guides

`wiki/Releasing.md`, `wiki/Building-from-Source.md`, `buildSrc/AGENTS.md`.
