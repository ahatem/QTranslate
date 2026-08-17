# Contributing to QTranslate

Thank you for taking the time to contribute. This document covers everything you need to know to get started.

---

## Table of Contents

- [Code of Conduct](#code-of-conduct)
- [Ways to Contribute](#ways-to-contribute)
- [Development Setup](#development-setup)
- [Branch Strategy](#branch-strategy)
- [Commit Messages](#commit-messages)
- [Opening a Pull Request](#opening-a-pull-request)
- [Architecture Overview](#architecture-overview)
- [Tests](#tests)

---

## Code of Conduct

Be respectful. Disagreements about code are fine; personal attacks are not. We are all here to build something good.

---

## Ways to Contribute

- **Bug reports** — open an issue using the Bug Report template
- **Feature requests** — open an issue using the Feature Request template
- **Bug fixes** — fork, fix, open a PR against `develop`
- **New features** — open an issue first to discuss before writing code
- **UI translations** — see [Adding a Language](wiki/Adding-a-Language.md)
- **Plugins** — see [Creating a Plugin](wiki/Creating-a-Plugin.md)
- **Documentation** — PRs improving the wiki or README are always welcome

---

## Development Setup

**Requirements**
- JDK 17 or later to build (we recommend [Temurin](https://adoptium.net)). Gradle's toolchain
  provisions JDK 21 for compilation, and CI builds on 17.
- Gradle (the wrapper is included — use `./gradlew`)

Kotlin and every other dependency come from the wrapper and the version catalog in
`gradle/libs.versions.toml`; nothing needs installing separately. The built JAR targets Java 11
bytecode, so users can run it on a Java 11 runtime.

**Steps**

```bash
# 1. Fork the repo on GitHub, then clone your fork
git clone https://github.com/YOUR_USERNAME/qtranslate.git
cd qtranslate

# 2. Add the upstream remote so you can sync later
git remote add upstream https://github.com/ahatem/qtranslate.git

# 3. Build everything
./gradlew build

# 4. Run the app
./gradlew :app:run -DappData="path/to/your/test/data"
```

Open the project in IntelliJ IDEA — it will detect the Gradle build automatically.

---

## Branch Strategy

We use a lightweight Git Flow:

```
main          ← always releasable, protected, tagged for releases
  └─ develop  ← integration branch, all PRs target this
       └─ feature/your-feature-name   ← your work
       └─ fix/short-description-of-bug
       └─ docs/what-you-are-documenting
```

**Rules**
- Never push directly to `main` or `develop`
- Branch off `develop` for all work
- Keep branches focused — one feature or fix per branch
- Delete your branch after it is merged

```bash
# Start new work
git checkout develop
git pull upstream develop
git checkout -b feature/my-feature

# When done, push and open a PR against develop
git push origin feature/my-feature
```

---

## Commit Messages

We follow [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<scope>): <short summary>

[optional body]

[optional footer]
```

**Types**

| Type | When to use |
|------|-------------|
| `feat` | A new feature |
| `fix` | A bug fix |
| `refactor` | Code change that is neither a fix nor a feature |
| `docs` | Documentation only |
| `test` | Adding or fixing tests |
| `chore` | Build system, dependencies, CI |
| `style` | Formatting, whitespace (no logic change) |

**Scopes** (optional but helpful): `core`, `api`, `ui`, `plugins`, `ci`

**Examples**

```
feat(core): add backward translation to TranslateTextUseCase
fix(ui): prevent font combos from blocking EDT on AppearancePanel
docs: add plugin submission guide to wiki
chore(ci): add release workflow for tag pushes
```

The summary line should be under 72 characters and written in the imperative mood ("add", "fix", "remove" — not "added", "fixed", "removes").

---

## Opening a Pull Request

1. Make sure your branch is up to date with `develop`:
   ```bash
   git fetch upstream
   git rebase upstream/develop
   ```
2. Run the full build locally: `./gradlew build` (this runs the tests too)
3. Push your branch and open a PR against `develop` on GitHub
4. Fill in the PR template — describe what changed and why
5. Link any related issues with `Closes #123`

**Review checklist (we check these)**
- [ ] Builds without warnings
- [ ] All existing tests pass
- [ ] New behaviour is covered by tests where practical
- [ ] MVI architecture respected — no logic in UI components
- [ ] Coroutine dispatchers used correctly (`Dispatchers.IO` for blocking I/O)
- [ ] No raw exceptions thrown for expected failures — use `Result`
- [ ] KDoc on public API surface

A maintainer will review within a few days. We may request changes — this is normal and not a rejection.

---

## Architecture Overview

QTranslate follows Clean Architecture with MVI for the UI layer. The full architecture guide is in [wiki/Architecture.md](wiki/Architecture.md). The key rules:

- **`:api`** — interfaces and data types only. No implementations. Plugins depend on this.
- **`:core`** — business logic, use cases, stores, repositories. No Swing imports.
- **`:ui-swing`** — Swing UI components. Implements `Renderable<State>`, dispatches `Intent`s.
- **`:app`** — composition root. Wires everything together. As thin as possible.
- **`:plugins/*`** — plugin implementations. Depend on `:api`, and on `:plugins:common` for shared
  HTTP, JSON and language-mapping helpers. Never on `:core` or `:ui-swing`.

UI components must be "dumb" — they render state and dispatch intents, nothing more.

---

## Tests

```bash
./gradlew test
```

Tests run on JUnit 5, with `kotlinx-coroutines-test` for suspend functions. CI runs them on every
push and a failure blocks the PR.

The suite is strongest where a mistake is quiet and expensive: configuration migration and
recovery, plugin loading and validation, the HTTP client's proxy/timeout/retry rules, the
localization file format, and document translation. Plugin modules test their own parsing and
language mapping against recorded responses rather than a live service. `plugins/common` publishes
a `FakePluginContext` test fixture for that, so a plugin test needs no network.

Thinner, and worth contributing to:

- **Use cases in `:core`** — most of `core/main/domain/usecase` has no direct coverage
- **Swing panels** — only the icon, theme and plugin-list models are covered
- **Stores** — `MainStore` and `SettingsStore` are exercised only indirectly

Open an issue titled `test: add tests for <class name>` before starting something substantial, so
two people don't write it twice. If you are new to testing Kotlin coroutines, the
[official testing guide](https://kotlinlang.org/docs/coroutines-testing.html) is a good start.