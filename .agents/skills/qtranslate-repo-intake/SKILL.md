---
name: qtranslate-repo-intake
description: Reconnoiter the QTranslate repo (git state, branch, history, diff, modules, tests) before touching code. Use at the start of every task.
---

# QTranslate Repo Intake

Run this before any edit. It prevents blind changes.

## Steps

1. `git status --short --branch` — record dirty files; they constrain everything.
2. `git branch --show-current` and `git log --oneline -10` — where am I, what moved.
3. `git diff --stat` (+ full `git diff` for small diffs) — what is already in flight.
4. Map the request to modules (`:api`, `:core`, `:ui-swing`, `:app`,
   `:plugins/*`, `buildSrc`, `.github`) using root `AGENTS.md` repository map.
5. Read the nested `AGENTS.md` for each affected subtree.
6. Find existing tests covering the area (`*Test.kt` near the target) and note
   gaps. Do not assume coverage.
7. Note related issues/PR context if the request references numbers
   (`gh issue view`, `gh pr view` — read-only).
8. Classify risk: R0 trivial / R1 bounded / R2 cross-module / R3 high-risk
   (settings migration, native input, updater/installer, packaging, public
   API, security-sensitive). See `docs/ai/WORKFLOW.md`.

## Output

A short intake note: branch, dirty state, affected modules + owners, relevant
tests, risk class, and what you will read next. Then stop — planning and
implementation are separate steps.
