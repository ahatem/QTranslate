# QTranslate Agentic Workflow

This file is the **canonical process definition**. `DEVELOPMENT_PLAYBOOK.md` is
a human-facing recipe that summarizes this; where they disagree, this file wins.

## Standard workflow

1. **Intake** — `qtranslate-repo-intake` (state, branch, diff, modules, tests).
2. **Classify risk** — R0 trivial / R1 bounded / R2 cross-module / R3 high-risk.
3. **Inspect** — read owning code + nested `AGENTS.md` + relevant docs; check history.
4. **Requirements** (substantial behavior only) — `qtranslate-requirements-interview`
   owns the QTranslate question set (platforms, persistence/migration,
   existing-user impact). `openspec-explore` is for weighing options *inside*
   an existing change, not for replacing the interview. When expected behavior
   is undefined or comes from a bug report, run `qtranslate-diagnose-bug` first.
5. **Specify** (OpenSpec, when required) — capture agreed behavior with
   `openspec-propose`; a human approves before coding. Implementation later
   runs through `openspec-apply-change`, which does **not** replace steps
   9–11: verify, diff review, and audit still apply.
6. **Plan** — `qtranslate-plan-change` for R1+ or anything unclear; plan mode
   for cross-module, settings, native input, packaging, security. The plan is
   *how*, never a restatement of *what*.
7. **Invariants** — state what must hold before editing.
8. **Implement** — smallest coherent change; one concern per branch off `develop`.
9. **Verify** — `qtranslate-verify-change` ladder (cheapest adequate first).
10. **Diff review** — `qtranslate-review-diff` (or auditor subagent for R2+).
11. **Audit** — independent auditors where risk justifies (R2+: one; R3: two).
12. **Repair** — fix findings narrowly, rerun smallest target.
13. **Broaden** — widen verification only when justified (never packaging runs
    for small unrelated changes).
14. **Preflight** — `qtranslate-pr-preflight`; honest test report. When the
    branch carried an OpenSpec change, archive it here with
    `openspec-archive-change` rather than in a later cleanup PR.
15. **User decides** — push/merge is always the user's call.

## When OpenSpec is required

Required for substantial R2/R3 changes to **product behavior**. Normally not
required for R0/R1, straightforward bugs, docs, CI/tooling, or
behavior-preserving refactors. Never required of external contributors.

| | OpenSpec | `qtranslate-plan-change` |
|---|---|---|
| Question | what behavior do we agree to build? | how does this repo implement it safely? |
| Owner of truth | `openspec/specs/`, `openspec/changes/` | the plan / task list |
| When | after requirements, before code | after behavior is agreed |

If implementation exposes an undecided product question, stop and update the
OpenSpec change first. Code must never become the decision-maker.

## Risk model

- **R0 trivial** (typo, locale wording): inspect → edit → `git diff --check` → done.
- **R1 bounded** (isolated bug, local UI, provider mapping fix): intake → short
  plan → implement → focused test → diff review.
- **R2 cross-module** (settings+UI, core+plugin contract): requirements
  interview when behavior is new, OpenSpec when the behavior change is
  substantial, formal plan, impact map, implementation, focused + integration
  verification, code auditor.
- **R3 high-risk** (settings migrations, native input, updater/installer,
  packaging, public API, security): requirements interview, OpenSpec for the
  behavior change, formal plan, explicit invariants, failure-mode analysis,
  targeted + broader tests, two audit perspectives, platform/release checks,
  final preflight.

Never escalate R0 into ceremony; never treat R3 casually. Risk drives depth;
it does not by itself mandate OpenSpec — see *When OpenSpec is required*.

## Plan-mode rule

Plan before editing when: requirements unclear, cross-module, architecture
change, settings migration, native input, packaging/release, security code,
hard regression. Direct implementation is fine for obvious isolated fixes,
small tests, docs, mechanical changes with an established pattern.

## Parallelism / worktrees

1. Multiple **read-only** agents may run in parallel freely.
2. Multiple **writers** must never share a working tree.
3. For genuinely parallel implementation: separate `git worktree`s on separate
   branches, disjoint file ownership each, one lead integrates.
4. Review the combined diff after integration — individually-correct diffs may
   not compose.
5. Command Code worktree features may be used when available; plain
   `git worktree` works everywhere. Keep worktree dirs outside the repo
   (ignored via `.git/info/exclude`, e.g. `.worktrees/`).

## Context engineering

- `AGENTS.md` = always-on. Nested files = subtree-only. Skills/references =
  on demand. Subagents = isolated exploration.
- Never stuff README/CONTRIBUTING/wiki into prompts; point and load lazily.
- Fresh session per unrelated task; compact only when useful.
- Summarize giant logs via a subagent; a 1M-token window is not permission to
  dump the repo into the prompt.
