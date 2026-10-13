---
name: qtranslate-diagnose-bug
description: Root-cause a QTranslate bug from evidence rather than suspicion, then fix it behind a regression test. Use for any bug report, flaky test, or unexpected behavior before editing code.
---

# QTranslate Diagnose Bug

Separate what was *reported* from what is *observed* from what is *proven*.

Run `qtranslate-repo-intake` first. Verification rungs come from
`qtranslate-verify-change`. Full process: `docs/ai/WORKFLOW.md`.

> Red CI is a different entry point: if the report is a failing GitHub Actions
> job, use `qtranslate-ci-diagnosis` first. Hand off here once it classifies the
> cause as a genuine product regression.

## Workflow

1. **Report** — restate the symptom, version/platform, and reproduction steps.
   If it came from an issue, read the whole issue; the title is not the spec.
2. **Reproduce** — get it to fail locally. No reproduction, no root cause claim.
3. **Minimize** — shrink to the smallest failing input or state.
4. **Feedback loop** — establish the fastest command that demonstrates the
   failure (a focused `:module:test --tests "<Class>"` is ideal). Re-run it
   freely; it must be cheap and deterministic.
5. **Hypotheses** — list candidate causes. State each as a falsifiable claim.
6. **Prove** — instrument or write a test that discriminates between them.
   Record the first hypothesis that survives, and the evidence for it.
7. **Protect** — add the failing test *first*. It must fail for the right
   reason, then pass once fixed.
8. **Fix** — smallest change that makes the test pass. Nothing else.
9. **Verify** — rerun the focused target, then widen only if risk justifies.
10. **Report** — symptom, evidence, confirmed cause, fix, regression test.

## Rules

- Never write "found suspicious code, changed it". That is not a diagnosis.
- Never widen a fix to adjacent code "while we are here".
- A flaky test is a hypothesis about ordering, timing, or shared state — not
  permission to add a retry. Get the interleaving before patching.
- If the reproduction fails to reproduce, say so. "Environment-specific" and
  "insufficient evidence" are valid verdicts.
- If the bug exposes undefined expected behavior, or two behaviors are both
  legitimate, stop and move to `qtranslate-requirements-interview` (and OpenSpec
  for substantial R2/R3 product behavior) before fixing.
- Verdicts: confirmed / likely but unreproduced / environment-specific /
  expected behavior / duplicate / insufficient evidence / product decision
  required.