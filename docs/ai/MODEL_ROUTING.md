# QTranslate Model Routing

> **Non-canonical and time-sensitive.** This is an operational shortlist for
> AI-tooling work only — it is not repository policy and is not part of the
> default agent context. It is not listed in the root `AGENTS.md` read-before-
> acting set, and ordinary feature work should never need it.
>
> Last validated against: `opencode` v2.0.27 and Command Code v1.80.0. Treat the
> catalog below as a dated snapshot that is expected to drift; re-check before
> relying on it. Shared runtime configuration stays model-free.

## Why lanes exist

Different tasks need different trade-offs. This file records *how to choose*, so
that a choice is deliberate rather than accidental. It does not prescribe a
model for anyone.

| Lane | Job | Wants |
|---|---|---|
| A. Lead/architecture | planning, hard debugging | reasoning reliability, long-context quality, tool-use consistency |
| B. Implementation | bounded Kotlin edits | code correctness, patch discipline, tool reliability, speed/cost balance |
| C. Exploration | repo search, recon | speed, cheap/free, good summarization |
| D. Test analysis | failure triage | log reading, flakiness judgment |
| E. Audit | independent review/security/release | independence from the implementer, skepticism, correctness over speed |
| F. Mechanical | formatting, locale strings, boilerplate | cheapest available |

## Selection priorities

- **Lead/planner**: reasoning + architecture + tool-use consistency first.
- **Implementer**: Kotlin correctness + patch discipline + cost balance.
- **Explorer**: speed and near-zero cost.
- **Auditor**: a *different model family* than the implementer when practical.
  Independence beats raw power here — see `WORKFLOW.md` step 9.
- Never optimize on public benchmarks alone; evaluate on real QTranslate tasks.

## Snapshot of available options (dated)

Observed on the validating machine; **informational only**, and expected to be
out of date.

- **OpenCode**: a Google family (Gemini flash/pro, Gemma) plus a small
  `opencode/*` free tier. The free tier is the practical default; the
  non-free models are for lead/implementation lanes.
- **Command Code**: a wide multi-vendor catalog (Anthropic, OpenAI, Google,
  Meta, xAI) plus open-weight families including DeepSeek, Kimi, GLM, MiniMax,
  Qwen and Mistral, several with free tiers.

Rather than re-listing model identifiers that rot, read the live catalog from
your own install:

```text
opencode models        # OpenCode
cmd --list-models      # Command Code
```

## Personal routing is not shared config

Shared configuration carries **no model pins** — `opencode.json`,
`.commandcode/settings.json`, and every agent definition. Every contributor
inherits their own session model. Personal choices belong in:

- Command Code → `.commandcode/settings.local.json` (git-ignored) or the
  planning/implementation model settings.
- OpenCode → `~/.config/opencode/`.

## Repo-specific evaluation rubric

Score candidates on real QTranslate tasks (1–5 each):

- **A — architecture comprehension**: multi-module change; did it name the
  right owner and boundaries?
- **B — bug diagnosis**: historical non-obvious bug; score the diagnosis.
- **C — bounded implementation**: small Kotlin change; score diff size,
  correctness, tests, architecture respect, absence of unrelated edits.
- **D — adversarial review**: seeded diff with known defects; score hits,
  false positives, architecture awareness.

Also track tool reliability, verification discipline (ran vs claimed), speed,
and cost. Update routing from this evidence, not hype.