---
name: qtranslate-ci-diagnosis
description: Diagnose a QTranslate CI failure from logs before touching product code. Use when any GitHub Actions job goes red.
---

# QTranslate CI Diagnosis

Change code only if the evidence supports it.

## Workflow

1. Identify the exact failed workflow, job, and step (`gh run view`, logs).
2. Read the log; find the FIRST meaningful failure (later errors are usually
   fallout). Summarize huge logs via a subagent; do not dump them into context.
3. Classify: product regression / test regression / infrastructure /
   flaky-external (provider, network) / platform-only / packaging.
4. Compare local vs CI environment (JDK 17 vs 21 toolchain, OS, display-less
   vs headed, macOS helper properties `-PmacVisionHelper`/`-PmacSpellHelper`).
5. Reproduce with the narrowest local target
   (e.g. the single failing module test, `validate*` task, plugin smoke).
6. Fix only the evidenced cause. One failing macOS/Windows job never justifies
   rewriting unrelated code or rerunning the whole matrix blind.

## Pipelines (see `.github/AGENTS.md`)

- `ci.yml`: `build` (Ubuntu, `./gradlew build` + buildSrc tests) runs
  **independently of** `macos-helper`; `release-variants` needs `macos-helper`;
  `portable-runtime` and `windows-candidate` need `release-variants`;
  `release-inventory` needs `macos-helper` + `windows-candidate`;
  `macos-candidate` needs `release-variants`. Check `needs:` rather than
  assuming visual order is dependency order.
- Pushes to `main`/`develop` skip CI only for the five named root Markdown files
  plus `docs/**` and `wiki/**`; `pull_request` runs everything.
- `plugin-smoke.yml`: Windows `smokeTestAllPlugins`.
- `release.yml`: tag-gated; version-equality and artifact gates are R3.

## Rules

- Never weaken a workflow, assertion, or gate to make red green.
- Report: failed job/step, first error (quoted), classification, evidence,
  narrowest fix or rerun, and what was NOT the cause.
