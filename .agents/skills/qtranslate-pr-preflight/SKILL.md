---
name: qtranslate-pr-preflight
description: Final readiness check before a QTranslate PR covering diff, architecture, tests actually run, compatibility risks, docs, and commit quality. Use last, before pushing.
---

# QTranslate PR Preflight

Produce a concise PR-ready report. Never fabricate a test result.

## Inspect

`git status --short --branch`, full `git diff`, changed-file list, branch
lineage (off `develop`, one concern), issue linkage (`Closes #NNN`).

## Check

- Architecture: ownership boundaries held, MVI respected, no logic in views,
  `Dispatchers.IO` for blocking I/O, KDoc on public API, additive API change.
- Tests: which ran and their real results (`passed/failed/skipped/not run`);
  new/changed behavior covered; no test weakened to get green.
- Compatibility: settings migration safe, provider contracts intact,
  portable/installed semantics unchanged, no dependency upgrades, no
  generated files touched.
- Docs: README/wiki/CHANGELOG updated only if behavior changed; UI changes
  carry before/after screenshots; PR template filled truthfully.
- OpenSpec: if this branch carried an OpenSpec change, the behavior in the
  spec matches the implementation and its tests (`spec ↔ code ↔ tests` agree),
  and the change is archived with `openspec-archive-change` here — on the
  substantive branch, not in a later cleanup PR. Skip when no change exists.
- Commits: Conventional Commits, imperative ≤72 chars, no AI/model/vendor
  attribution or trailers, no unrelated files.

## Verdict

`READY` (ship it) or `BLOCKED` (numbered reasons). The user decides whether
to push — you never push or merge.
