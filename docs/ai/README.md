# QTranslate AI Engineering Setup

Maintainer guide to the agentic development environment. Product rules live in
`AGENTS.md`; this directory explains the machinery.

## What is what

- **`AGENTS.md` (repo root)** — canonical, always-loaded repository
  instructions. Concise on purpose: module map, invariants, workflows,
  git/security rules. Every agent reads it.
- **Nested `AGENTS.md`** (`api/`, `core/`, `ui-swing/`, `plugins/`,
  `buildSrc/`, `.github/`) — subtree-only rules, loaded when working there.
- **`CLAUDE.md`** — compatibility adapter: imports `AGENTS.md` for Claude
  Code. No duplicated policy.
- **`.agents/skills/`** — the single canonical skill library (Agent Skills
  standard: `<name>/SKILL.md` + optional `references/`, `scripts/`,
  `assets/`). Discovered automatically by OpenCode **and** Command Code —
  no per-vendor copies. Holds the QTranslate skills plus the vendor-neutral
  `openspec-*` skills installed by `openspec init --tools agents`
  (`propose`, `explore`, `apply-change`, `update-change`, `sync-specs`,
  `archive-change`). It also holds `.openspec-target`, a CLI marker recording
  which integration target was installed — **do not delete it**, or
  `openspec update`/re-init detection breaks. For Claude Code, `CLAUDE.md` is
  the supported integration; skill discovery there is deferred until it is
  verified end-to-end, so do not add `.claude/skills` mirrors.
- **`.opencode/agents/`** — OpenCode-native read-only auditors
  (`mode: subagent`, `edit: deny`, git/grep-only bash).
- **`.commandcode/agents/`** — Command Code-native auditors (read-only tool
  allowlist, `maxTurns: 40`, model inherits the session).
- **`.commandcode/settings.json`** — shared project config: safe permission
  defaults + deterministic hooks (`hooks/guard-shell.mjs`,
  `hooks/stop-preflight.mjs`). Personal model choices live in the
  git-ignored `settings.local.json`, never here.
- **`opencode.json`** — shared OpenCode config in native V2 syntax
  (`permissions` allow/ask/deny rules): shell asks by default, read-only Git
  inspection and `grep` pre-approved, Git push and the destructive
  `reset --hard` / `clean -f` forms denied. No model pins.
- **`docs/ai/WORKFLOW.md`** — **canonical** process definition: intake → risk →
  requirements → OpenSpec → plan → implement → verify → audit → preflight,
  plus worktree/parallel rules.
- **`docs/ai/DEVELOPMENT_PLAYBOOK.md`** — human-facing quick recipe. Summarizes
  and links to `WORKFLOW.md`; never a second source of truth.
- **`docs/ai/MODEL_ROUTING.md`** — non-canonical, dated model-selection lanes
  and evaluation rubric. Load it only for AI-tooling, routing, or
  agent-maintenance work; it is deliberately not default reading.

## OpenSpec (spec-driven behavior)

`openspec/` holds agreed **product behavior**; the CLI is `openspec`
(v1.14.1). Installed with the vendor-neutral shared-agents integration, so the
`openspec-*` skills live in the same `.agents/skills/` directory the other
tools already discover — no per-vendor copies.

| | OpenSpec | `qtranslate-plan-change` |
|---|---|---|
| Question | what behavior do we agree to build? | how does this repo implement it safely? |
| Lives in | `openspec/specs/`, `openspec/changes/` | the plan / task list |

When OpenSpec is required, and what it must stay separate from, is defined in
[`WORKFLOW.md`](WORKFLOW.md#when-openspec-is-required).

Project constraints for generated artifacts live in `openspec/config.yaml`
(`context:`) — keep that short, and put discoverable codebase facts in
`AGENTS.md` instead.

## How strong the shell policy actually is

Defense in depth, not a sandbox. Be precise about this when explaining it:

- **Unmatched commands fall back to `ask`.** OpenCode uses `ask` when no rule
  matches, and Command Code prompts in its `default` permission mode. Nothing
  destructive becomes *silently approved* because it was not recognized.
- **Git push is denied in both tools.** In OpenCode that is a `permissions`
  deny rule; in Command Code it is a `deny` rule (deny beats allow) plus the
  `guard-shell.mjs` hook. Both cover `git push` with Git global options in
  between (`git -C … push`, `git -c … push`, `git --git-dir=… push`).
- **`experimental.policies` is deliberately absent.** The V2 docs describe it as
  a hard deny that also overrides "Allow always" saved approvals, but in
  `opencode` 2.0.27 every statement shape — including the documented
  `provider.use` and `permission` forms — is dropped at load time with
  `skipped malformed recognized value`. Shipping it would add log warnings and
  zero protection, so it stays out until it is verified to load. Re-test before
  re-adding.
- **Shell command matching is best-effort** in both tools. Do not claim
  exhaustive coverage of destructive commands.

## Hook failure policy

The two hooks fail in opposite directions, on purpose:

| Hook | `failClosed` | Why |
|---|---|---|
| `guard-shell.mjs` (PreToolUse) | `true` | A crash or timeout must block, not silently wave a destructive command through. |
| `stop-preflight.mjs` (Stop) | unset | A quality nudge should never be able to wedge a session. Its failure is reported and the turn ends normally; the engine also caps Stop retries at 3. |

Command Code project config is resolved against the **launch directory**
(`~/.commandcode/settings.json`, then `<cwd>/.commandcode/settings.json`, then
`settings.local.json`; hooks also run with `cwd` = launch directory). Start
`cmd` from the repository root, or the project settings — and therefore the
guard — will not load at all. That is a single consistent rule, not a path bug.

## Tool discovery

| Tool | Looks at |
|---|---|
| OpenCode | `AGENTS.md` (walk-up), `.agents/skills/` (incl. `openspec-*`), `.opencode/agents/`, `opencode.json` |
| Command Code | `AGENTS.md` (memory), `.agents/skills/` (+ `.commandcode/skills/` wins on conflict), `.commandcode/agents/`, `.commandcode/settings.json` |
| Claude Code | `CLAUDE.md` → `AGENTS.md`; skills deferred (see above) |

## Testing the guard

`guard-shell.mjs` exports its matcher and ships a dependency-free regression
matrix. Run it after any change to the guard:

```text
node --test .commandcode/hooks/guard-shell.test.mjs
```

It must pass before the bootstrap changes are committed.

## Known documentation drift (tracked, not yet fixed)

- `wiki/Architecture.md` states that `:core` has no Swing imports and that
  `:app` is a thin composition root. Source contradicts both: `:core` imports
  `javax.swing.KeyStroke` and several `java.awt` value types, and `:app` also
  owns `AppDataLayout` and the screenshot harness. `AGENTS.md` now describes
  reality; the wiki page is stale and needs a separate update. Product and wiki
  files are out of scope for this bootstrap change.
- `wiki/Building-from-Source.md` and `README.md` do not document the
  `portable.flag` / portable-vs-installed data-location semantics that
  `AppDataLayout` implements and the packaging scripts assert.
- `README.md` says `Ctrl+L` "steps through pinned languages".
  `MainStore.handleCycleTargetLanguage()` actually cycles
  `availableLanguages` minus `AUTO`, matching the `HotkeyBinding` KDoc;
  `Configuration.pinnedLanguages` is a separate concept. `ui-swing/AGENTS.md`
  follows the implementation.

## Daily use

- Trivial (typo, locale string): read the file, edit, `git diff --check`, done.
- Normal bug/feature: follow [`WORKFLOW.md`](WORKFLOW.md) — it is canonical.
  Short version: `qtranslate-repo-intake` → `qtranslate-diagnose-bug` for bugs,
  add `qtranslate-requirements-interview` + OpenSpec for substantial new
  behavior → `qtranslate-plan-change` → implement → `qtranslate-verify-change` →
  `qtranslate-review-diff` → `qtranslate-pr-preflight`.
- CI red: `qtranslate-ci-diagnosis` before touching code.
- Settings/native/packaging/API/security: corresponding skill + R3 workflow +
  two auditors + `qtranslate-pr-preflight`. Never push; the user decides.

## Maintain this setup

Review it when: OpenCode/Command Code major version or schema changes,
module ownership or build/test commands change, packaging strategy changes,
or recurring agent mistakes reveal a missing invariant. Promote a rule only
for recurring, important invariants — never for one-off mistakes.
