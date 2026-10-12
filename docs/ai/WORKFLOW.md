# QTranslate Agentic Workflow

## Standard workflow

1. **Intake** — `qtranslate-repo-intake` (state, branch, diff, modules, tests).
2. **Classify risk** — R0 trivial / R1 bounded / R2 cross-module / R3 high-risk.
3. **Inspect** — read owning code + nested `AGENTS.md` + relevant docs; check history.
4. **Plan** — `qtranslate-plan-change` for R1+ or anything unclear; plan mode
   for cross-module, settings, native input, packaging, security.
5. **Invariants** — state what must hold before editing.
6. **Implement** — smallest coherent change; one concern per branch off `develop`.
7. **Verify** — `qtranslate-verify-change` ladder (cheapest adequate first).
8. **Diff review** — `qtranslate-review-diff` (or auditor subagent for R2+).
9. **Audit** — independent auditors where risk justifies (R2+: one; R3: two).
10. **Repair** — fix findings narrowly, rerun smallest target.
11. **Broaden** — widen verification only when justified (never packaging runs
    for small unrelated changes).
12. **Preflight** — `qtranslate-pr-preflight`; honest test report.
13. **User decides** — push/merge is always the user's call.

## Risk model

- **R0 trivial** (typo, locale wording): inspect → edit → `git diff --check` → done.
- **R1 bounded** (isolated bug, local UI, provider mapping fix): intake → short
  plan → implement → focused test → diff review.
- **R2 cross-module** (settings+UI, core+plugin contract): formal plan, impact
  map, implementation, focused + integration verification, code auditor.
- **R3 high-risk** (settings migrations, native input, updater/installer,
  packaging, public API, security): formal plan, explicit invariants,
  failure-mode analysis, targeted + broader tests, two audit perspectives,
  platform/release checks, final preflight.

Never escalate R0 into ceremony; never treat R3 casually.

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
