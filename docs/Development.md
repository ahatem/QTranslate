# Development workflow

Use this workflow for human and automated contributions. Setup, branch/commit
conventions, test commands and PR requirements live in [CONTRIBUTING](../CONTRIBUTING.md).
Technical ownership is explained in [Architecture](../wiki/Architecture.md).
Keep one authoritative home for a rule; link specialist guides rather than copying them.

## Understand and agree

Inspect the branch, dirty files, recent history and diff. Read the owning source,
scoped instructions and tests before editing. Preserve unrelated and local data.
For a behavior change, establish the trigger, happy path, empty/failure/cancel
states, persistence, existing-user impact and platform differences. Record material
choices in the issue, PR, execution plan or a short normal design note. No special
specification framework or artifact lifecycle is required. Ask only about material
ambiguity that source/tests and the agreed request cannot resolve.

## Risk and planning

| Risk | Examples | Required depth |
|---|---|---|
| R0 | Wording, locale typo | Inspect, edit, diff check |
| R1 | Bounded bug or local change | Short plan, focused verification, diff review |
| R2 | Cross-module behavior/ownership | Impact map, characterization, focused/integration checks, independent review |
| R3 | Settings persistence, native input, plugin API, trust/security, release | Explicit failure/rollback analysis, targeted and relevant broader checks, two independent audit perspectives |

Plan before cross-module, settings, native, packaging, security or unclear work.
An approved plan authorizes its implementation; do not request the same approval
again. A plan identifies current evidence, owner, invariants, compatibility,
platform effects, steps, test matrix, rollback and genuinely unresolved choices.
Risk determines review depth, not a fixed amount of ceremony or a new framework.

## Diagnose bugs and failed checks

Distinguish reported, observed and proven behavior. Reproduce and minimize the
failure, establish the cheapest deterministic feedback command, test competing
hypotheses and add a regression that fails for the right reason before fixing it.
Use controlled interleavings for concurrency; do not hide flakes with sleeps/retries.
If reproduction is missing, say so rather than asserting a root cause.

For CI, identify the failed job/step and first meaningful error. Classify product,
test, infrastructure, external/flaky, platform or packaging failure; compare the
actual JDK/toolchain, OS/display and helper properties. Fix the evidenced cause.
Never weaken a workflow or assertion. Read pipeline `needs:` rather than assuming
visual order, and avoid blanket reruns or matrix changes for one-off diagnosis.

## Implement and verify coherent slices

Keep changes small and independently reviewable. Characterize behavior before
structural work; fix unsafe lifetimes before moving the affected code. Prefer
immutable requests, narrow typed outcomes and one operation owner. Expected
failures are typed at plugin/application boundaries; internal adapter exceptions
may abort work when caught once at the boundary. Always preserve coroutine
cancellation. Blocking adapters own their IO context; compute belongs on Default.

Use the cheapest adequate checkpoint:

1. Inspect references/diff during mechanical edits; compile affected code when needed.
2. Run `git diff --check` and focused tests after a coherent behavior/ownership slice.
3. Run owning module/integration tests after subsystem completion.
4. Run the canonical build before PR readiness, as required by CONTRIBUTING.
5. Run release/package checks only for release-affecting work.

Report each check as passed, failed, skipped, not run or not applicable. On failure,
inspect first, fix narrowly and rerun the smallest target before widening. Never
claim checks ran when they did not. Existing behavior coverage must not be removed
or weakened for a refactor. Source-spelling assertions are not substitutes for
runtime behavior tests; preserve their intended protection when moving code.

## Package and ownership map

The authoritative map, including what each workspace package owns, lives in
[`wiki/Architecture.md`](../wiki/Architecture.md#workspace-ownership). Keep it in one place: add
findings and rules here only when they are about the development process, and link to the
architecture guide for ownership.

Place a responsibility with its owner before organizing packages around it. Move one coherent
feature at a time and update tests/imports in the same change. Prefer moving a class to adding a
compatibility alias; UI packages mirror the feature they render.

## Verification expectations by slice

- Behavior changes need a deterministic regression that fails for the right reason.
- Concurrency/ownership changes need controlled interleavings (gates, virtual time or real
  threads for lock-order claims) and must preserve coroutine cancellation.
- Privacy/diagnostics changes use sentinel values and assert they never reach logs or history.
- Persistence changes cover malformed input, recovery, migration round trips and shutdown drain.
- Mechanical package moves prove rename fidelity and run the owning module suites plus the
  canonical build; source-string checks supplement, never replace, behavior tests.

## Specialist changes

### Settings and storage

Preserve draft/Apply/Cancel versus scoped saves, versions, defaults, storage keys
and single-DataStore-per-file ownership. Schema changes require versioned,
idempotent migrations and previous-representation round trips. Test malformed/
partial input, backup plus surfaced recovery, newer-version handling and rollback.
Exercise installed, portable and custom `-DappData` resolution through AppDataLayout.
Never silently discard data or migrate secrets as an incidental refactor.

### Swing and native input

Render on the EDT without blocking. UI owns toolkit focus/layout/lifecycle, while
application features own business policy. Preserve keyboard access, multilingual
fonts, DPI, real RTL mirroring and accessibility. Verify visual changes in the
running app and attach before/after screenshots; headless tests do not prove focus
or screen-reader behavior. Do not enumerate fonts on the EDT.

Keep global input behind GlobalInputBackend. Preserve registration tokens,
failed-unregister handling, local/global distinction, held-key safety, modifier
neutralization and monotonic timing. Meaning changes at the native boundary can
require ABI/version changes even if signatures match. For affected Windows,
macOS, X11 and Wayland behavior, record registration, raw input, lifecycle,
Double Ctrl, selection/outside click and injection as verified, reasoned-only or
not applicable. Reasoned-only never means tested. Platform predicates should
remain testable without that platform; real OS checks validate integration.

### Providers, public API and releases

Use [Creating a plugin](../wiki/Creating-a-Plugin.md) for provider development.
Keep metadata/manifest/service registration consistent, language mapping grounded
in the provider contract, and tests offline using recorded/scripted responses.
Inbound detection and outbound language codes are separate contracts; do not
silently map unsupported languages to a nearby language. Evolve the public API
with source and binary compatibility in mind; defaults alone do not prove ABI safety.

Use [Releasing](../wiki/Releasing.md) for version, inventory, size, bytecode,
metadata/checksum and native-helper gates. Tag and application version must agree.
Portable markers and install/upgrade/uninstall preservation checks remain mandatory.
Do not change artifact budgets, publish unsigned internal MSI packages or loosen
release assertions as an incidental fix.

## Review and completion

Review correctness, ownership, compatibility, cancellation, privacy, platform
assumptions and changed behavior coverage. Independent reviewers cite material
findings by file/line, ordered P0-P3 with a concrete remedy; `No findings.` is valid.
R2 needs an independent audit; R3 needs two complementary perspectives. Resolve
findings, rerun relevant checks and inspect the combined diff after parallel work.

Before delivery, check scope, conflict markers/debug artifacts, docs, public API,
settings/provider compatibility and actual verification results. State remaining
limitations and rollback concerns. Follow the PR checklist; the user controls
push/merge. Local/personal tools and reusable skills remain optional.
