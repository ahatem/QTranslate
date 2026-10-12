---
name: qtranslate-review-diff
description: Adversarial read-only review of the current QTranslate diff, ordered by severity with file/line citations. Use after verification, before preflight.
---

# QTranslate Review Diff

Read-only. Do not edit code; report findings. Hunt problems — `No findings.`
is an acceptable outcome. Never invent issues.

## Review categories

Correctness, regressions, architecture ownership (`:api`/`:core`/`:ui-swing`/
`:app`/`:plugins` boundaries), public API compatibility, settings
compatibility, coroutines/dispatchers, Swing EDT/focus/keyboard behavior,
cross-platform assumptions, native-input implications, error handling
(`Result` vs throw), privacy/logging (no user text, keys, bodies),
tests (coverage of changed behavior, boundary + negative paths), CI/release
impact, unnecessary complexity or scope creep.

## Finding format (ordered by severity P0 critical → P3 minor)

```text
[P0] path/to/File.kt:123-140 — reason + user-visible or engineering impact.
Recommended fix: concrete change.
```

## Rules

- Every finding cites file and line/range. Vague findings are dropped.
- Material issues only — no style nitpicks unless they prevent a real problem.
- Verify each claim against the actual code before reporting it; false
  positives cost more than silence.
- End with a verdict: block (P0/P1 open) or nothing blocking.
