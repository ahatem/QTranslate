---
name: qtranslate-requirements-interview
description: Turn a vague feature request or undefined behavior into agreed QTranslate requirements with explicit scope, edge cases, platform impact, and non-goals. Use before substantial R2/R3 behavior work, not for small or obvious tasks.
---

# QTranslate Requirements Interview

Produce agreement on **what the product should do** before anyone decides how
to build it. This is the input to OpenSpec (accepted behavior) and to
`qtranslate-plan-change` (implementation). It is not a substitute for either.

Full process and risk model: `docs/ai/WORKFLOW.md`.

## Do not use this for

- R0/R1 work, obvious bugs, docs, CI/tooling, or behavior-preserving refactors
  — those go straight to implementation or a short `qtranslate-plan-change`.
- Restating a request the user already answered precisely. Do not interview to
  fill silence; ask only what is genuinely undecided.

## Questions that must be answered

Answer these with the user, in this order. Stop when each has a concrete answer.

- Who is this for, and what problem does it solve?
- What triggers it? (menu action, hotkey, lifecycle event, config change)
- Happy path, step by step.
- Failure, empty, and cancel states — individually.
- What persists? What must survive restart?
- What happens to existing users, settings, and saved state? Is a
  `configVersion` bump and migration required?
- Which platforms differ (Windows / macOS / Linux-X11 / Linux-Wayland)?
- What is explicitly out of scope?
- Which existing behavior does this interact with or risk regressing?
- Are there two defensible readings of the request? If so, this needs a decision,
  not a guess.

## Output

A short requirements note:

```text
Problem         — who hurts, how, today
Behavior        — triggers, happy path, failure/empty/cancel states
Persistence     — what is stored, migration and existing-user impact
Platform        — per-platform differences and what stays untested
Out of scope    — explicitly excluded
Interactions    — existing behavior touched or at risk
Open questions  — only genuinely undecided items
Risk            — R0/R1/R2/R3, and whether OpenSpec is required
```

## Rules

- Never invent product decisions. If an answer is missing, ask.
- Prefer one question at a time over a batch of ten.
- Record rejected alternatives; they prevent re-litigating later.
- Keep the note behavior-focused. Module names, task ordering, and test commands
  belong to `qtranslate-plan-change`.
- If this interview is for a bug whose expected behavior is undefined, pair it
  with `qtranslate-diagnose-bug` first so the symptom is pinned down.