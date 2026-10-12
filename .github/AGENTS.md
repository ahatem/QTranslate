# `.github/` — CI and release automation

Pipelines are shared infrastructure: every PR runs through them, releases
depend on them. Read-only caution by default.

- Diagnose before touching: identify the exact failed workflow/job/step, read
  the log, find the first meaningful failure, classify it (product regression,
  test regression, infra, flaky/external, platform-only, packaging). See
  `qtranslate-ci-diagnosis`. Never rewrite a workflow to make red green.
- Least privilege: keep `permissions:` blocks minimal; pin third-party actions
  to full SHAs per repo policy; no `curl | sh` install steps.
- Do not expand matrices or add jobs for one-off debugging. `ci.yml` skips
  docs-only **pushes** to `main`/`develop` via `paths-ignore`, and only for
  `README.md`, `CHANGELOG.md`, `CONTRIBUTING.md`, `SECURITY.md`, `SPONSORS.md`,
  `docs/**` and `wiki/**` — not `*.md`, and not root or nested `AGENTS.md`.
  `pull_request` has **no** path filter, so docs-only PRs still run the full
  matrix. Keep that split as it is.
- Release flow (`release.yml`, tag `v*.*.*`) is R3: version comes from
  `AppConstants` and must equal the tag; MSI is internal-only (unsigned, never
  published); macOS helpers arrive via `-P` properties. Touch only with
  `wiki/Releasing.md` open and a stated reason.
- Windows packaging scripts (`.github/scripts/`) assert `portable.flag`
  inclusion/exclusion and user-data preservation across install/upgrade/
  uninstall — never loosen those assertions.
- PR template and CODEOWNERS (`@ahatem` everywhere) are maintainer-owned;
  do not edit them as part of feature work.
