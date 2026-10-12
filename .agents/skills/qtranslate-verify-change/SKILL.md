---
name: qtranslate-verify-change
description: Pick the cheapest adequate verification for a QTranslate change using the testing ladder. Use after every implementation before review.
---

# QTranslate Verify Change

Climb only as high as the risk justifies. Stop when confidence is adequate.

## Ladder

1. Static inspection — re-read the diff, check invariants by eye.
2. `git diff --check` — whitespace, conflict markers. Always.
3. Focused module tests — e.g. `.\gradlew.bat :core:test --tests "<Class>"`.
4. Affected integration tests (stores, plugin load, migration round-trips).
5. Cross-module check — `./gradlew build` equivalent scope only if R2+.
6. Full `.\gradlew.bat build` — the canonical CI gate; run before any PR.
7. Packaging/release validation (`assemblePortable`, `verifyReleaseArtifacts`,
   Windows installer scripts) — only for release-affecting changes.

> Do not run expensive release/packaging workflows for an unrelated small
> code change. Use `--rerun-tasks` only where it is useful, not ritually.

## Rules

- Report honestly per check: `not run / passed / failed / skipped / n/a`.
  Never fabricate a result. Never claim "tests pass" unless they ran.
- On failure: inspect the failure → decide code-fault vs test-fault → fix
  narrowly → rerun the smallest target → widen only after local cause is known.
- Never weaken, delete, or silently adjust an existing test to get green. If
  new behavior genuinely changes an expectation, stop and report which test
  and why.
- Respect CONTRIBUTING.md PR requirements (`./gradlew build` passes, MVI,
  `Dispatchers.IO`, KDoc on public API).
