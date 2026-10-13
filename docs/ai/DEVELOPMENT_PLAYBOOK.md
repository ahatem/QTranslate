# QTranslate Development Playbook

A practical recipe for day-to-day work. **It is not policy.** The canonical
definitions live in:

| For | See |
|---|---|
| the process itself | [`WORKFLOW.md`](WORKFLOW.md) — canonical, wins on any conflict |
| repository rules & invariants | [`../../AGENTS.md`](../../AGENTS.md) |
| how the agent tooling is wired | [`README.md`](README.md) |
| release process | [`../../wiki/Releasing.md`](../../wiki/Releasing.md) |

If this page and `WORKFLOW.md` disagree, `WORKFLOW.md` is right and this page is
a bug.

---

## The universal opening move

`qtranslate-repo-intake`, then classify the risk. Everything below hangs off
that classification, and the full risk model is in
[`WORKFLOW.md#risk-model`](WORKFLOW.md).

## Start here

Workflows below are shorthand (`repo-intake` = `qtranslate-repo-intake`, etc.).
The **OpenSpec?** column is a routing hint only — the authoritative rule is in
[`WORKFLOW.md`](WORKFLOW.md#when-openspec-is-required).

| I want to… | Workflow | OpenSpec? |
|---|---|---|
| Add a small feature | `repo-intake` → `plan-change` | no |
| Add a substantial feature | `repo-intake` → `requirements-interview` → OpenSpec → `plan-change` | yes (R2/R3 behavior) |
| Fix an obvious bounded bug | `repo-intake` → `diagnose-bug` | no |
| Fix a bug with undefined/ambiguous behavior | `diagnose-bug` → `requirements-interview` | maybe/yes |
| Triage a GitHub issue | `repo-intake` → classify → `diagnose-bug` (bug) or `requirements-interview` (feature) | only if accepted and substantial |
| Review a PR | `repo-intake` → `review-diff` (+ auditors for R2+) | read the existing change if one exists |
| Respond to PR feedback | classify feedback → fix narrowly, or update OpenSpec first if behavior changes | only if behavior changes |
| Refactor internals | `repo-intake` → `plan-change` (+ characterization tests) | no, if behavior is preserved |
| Provider/plugin change | `repo-intake` → `plugin-provider` | depends on behavior scope |
| Swing/UI change | `repo-intake` → `swing-ui` | only for substantial UX semantics |
| Persisted settings change | `repo-intake` → `settings-migration` (R3) | usually yes |
| Native-input change | `repo-intake` → `native-input` (R3) | normally yes |
| Dependency update | `repo-intake` → inspect pinned dep + upstream notes → narrow update | only if behavior changes |
| CI red | `ci-diagnosis` | no |
| Docs-only change | `repo-intake` → edit → `git diff --check` | no |
| Release/packaging change | `release-packaging` (R3) | only if product semantics change |
| Security-sensitive change | `repo-intake` + `security-auditor` (R3) | usually yes |
| Prepare your own PR | `pr-preflight` | archive the change here |

Issue triage and PR review are **composed from the skills above** rather than
having dedicated skills.

## Before implementing substantial behavior

Run `qtranslate-requirements-interview`. It carries the full question set —
trigger, happy path, failure/empty/cancel states, persistence and migration,
existing-user impact, platform differences, out of scope, and interactions.
If you cannot answer those, do not start coding.

## Bugs

`qtranslate-diagnose-bug` is evidence-driven and owns the reproduce →
minimize → prove → regression-test sequence. Whether a bug needs OpenSpec is
decided there and in [`WORKFLOW.md`](WORKFLOW.md#when-openspec-is-required);
do not re-derive the threshold here.

## Spec vs plan — keep them separate

OpenSpec answers *what behavior we agreed to build*; `qtranslate-plan-change`
answers *how QTranslate implements it safely*. Do not collapse them into one
document. If implementation reveals an undecided product question, stop and
update the spec — code must never become the decision-maker.

Full lifecycle and boundaries:
[`WORKFLOW.md#when-openspec-is-required`](WORKFLOW.md).

## The rules worth remembering

- **New behavior:** understand → specify if substantial → plan → build → verify → review.
- **Broken behavior:** reproduce → diagnose → regression test → fix → verify → review.
- **Reviewing someone else's work:** understand intent → check behavior/spec →
  implementation → tests → report only real findings.
- **CI:** diagnose first, edit second.
- **High-risk work:** plan deeply, test broadly, audit independently.
- **Small work:** keep it small.

The complete version of all of the above is
[`WORKFLOW.md`](WORKFLOW.md). This page is only the quick index.