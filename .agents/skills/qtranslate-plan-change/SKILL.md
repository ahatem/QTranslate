---
name: qtranslate-plan-change
description: Turn a QTranslate change request into an implementation-ready engineering plan with scope, invariants, risks, steps, and a test matrix. Use before R1+ edits.
---

# QTranslate Plan Change

Size the plan to the task: a one-file bug gets half a page, a cross-module
change gets the full template. Write the plan, get approval for R2+, then
implement.

## Plan template

```text
Goal / Non-goals
Current behavior (with file:line evidence)
Affected modules + ownership boundaries
Invariants that must hold (from AGENTS.md + nested files)
Data/settings compatibility (configVersion? migration? defaults?)
Platform impact (Windows/macOS/Linux, Wayland/X11, portable/installed)
Failure modes / what-ifs (what breaks, how it surfaces, recovery)
Implementation steps (ordered, smallest coherent slices)
Test matrix (new + existing tests per step)
Rollback/recovery considerations
Open assumptions (explicit unknowns, not guesses)
```

## Rules

- Cite file paths and line numbers for every behavior claim. No evidence, no plan.
- Name the risk class (R0–R3) and the workflow depth it implies.
- For R3 (settings, native input, packaging, public API, security), failure
  modes and rollback are mandatory sections, not optional.
- Never plan unrelated refactors, dependency upgrades, or generated-file edits.
- If requirements are unclear, ask the user instead of filling gaps silently.
