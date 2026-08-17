# Contributing Guide

This page expands on [CONTRIBUTING.md](https://github.com/ahatem/qtranslate/blob/main/CONTRIBUTING.md) with more detail on specific workflows.

---

## Setting up your environment

### 1. Fork and clone

```bash
# Fork on GitHub first, then:
git clone https://github.com/YOUR_USERNAME/qtranslate.git
cd qtranslate
git remote add upstream https://github.com/ahatem/qtranslate.git
```

### 2. Keep your fork in sync

Before starting any new work, sync with upstream:

```bash
git fetch upstream
git checkout develop
git merge upstream/develop
git push origin develop
```

### 3. Run the app locally

```bash
./gradlew :app:run -DappData="C:/Users/you/QTranslateTestData"
```

Use a dedicated test data directory — separate from any real installation — so you can install test plugins and mess with settings freely.

---

## Working on a feature

```bash
# 1. Branch off develop — always
git checkout develop
git pull upstream develop
git checkout -b feature/my-feature-name

# 2. Make your changes, commit often
git add .                           # stage everything
git commit -m "feat(core): add X"

# 3. Keep your branch up to date if develop moves on
git fetch upstream
git rebase upstream/develop

# 4. Push and open a PR
git push origin feature/my-feature-name
```

Open the PR against `develop`, not `main`.

---

## Commit message format

We use [Conventional Commits](https://www.conventionalcommits.org/). This lets us generate changelogs automatically.

```
<type>(<scope>): <summary>

[optional body — explain WHY, not WHAT]

[optional footer — Closes #123, Breaking-Change: ...]
```

**Types:** `feat`, `fix`, `refactor`, `docs`, `test`, `chore`, `style`

**Scopes:** `core`, `api`, `ui`, `plugins`, `ci`, `build`

**Examples:**
```
feat(ui): add dirty indicator to SettingsDialog title bar
fix(core): prevent double DataStore instance on startup
refactor(plugin): extract PluginInstaller from PluginManager
docs(wiki): add plugin submission guide
chore(deps): bump FlatLaf to 3.8
```

---

## Code style

We don't enforce a strict formatter, but please follow these conventions:

- **Kotlin idioms** — use `when`, `let`, `run`, `fold`, `mapValues` etc. instead of verbose if/else chains
- **No raw exceptions for expected failures** — return `Result<T, Error>` instead
- **No business logic in UI components** — dispatch an intent, let the store handle it
- **Blocking I/O on `Dispatchers.IO`** — wrap `Files.*`, JDBC, synchronous HTTP in `withContext(Dispatchers.IO)`
- **KDoc on public API** — everything in `:api` and `:core`'s public surface needs KDoc

---

## Module rules

| Module | Can import | Cannot import |
|--------|-----------|---------------|
| `:api` | stdlib, kotlinx-coroutines (for suspend), kotlin-result | `:core`, `:ui-swing`, Swing |
| `:core` | `:api`, kotlinx, DataStore, Ktor | `:ui-swing`, Swing |
| `:ui-swing` | `:api`, `:core`, Swing, FlatLaf | `:app` |
| `:app` | everything | — |
| `:plugins:*` | `:api`, `:plugins:common` | `:core`, `:ui-swing` |

Plugins importing `:core` is the most common mistake — if you find yourself needing something from `:core` in a plugin, it probably belongs in `:api` instead.

---

## Tests

```bash
./gradlew test
```

CI runs the suite on every push and a failure blocks the PR.

**Test stack:**
- JUnit 5 for test structure
- `kotlinx-coroutines-test` for suspend functions and flows
- `ktor-client-mock` for scripting HTTP responses
- `FakePluginContext`, published as a test fixture from `:plugins:common`, for plugin tests

**Where coverage is thin:** most use cases in `core/main/domain/usecase`, the Swing panels, and
both MVI stores. Good places to start.

**How to contribute a test:**

1. Pick a class with no direct coverage — check `src/test/kotlin` in the relevant module first
2. Open an issue: `test: add tests for <ClassName>` — a maintainer will label it `good first issue`
3. Add tests under `src/test/kotlin` in the relevant module
4. Open a PR — even a single well-written test is a meaningful contribution

**Useful resources:**
- [Kotlin coroutines testing guide](https://kotlinlang.org/docs/coroutines-testing.html)
- [JUnit 5 user guide](https://junit.org/junit5/docs/current/user-guide/)
- [MockK documentation](https://mockk.io/)

For UI changes, attach before/after screenshots to the PR — we do not have automated UI tests.

---

## Adding a new service type

If you want to add a completely new service category (e.g. a grammar checker) rather than a new plugin for an existing category:

1. Add the interface to `:api` — discuss in an issue first since this is a breaking change surface
2. Add the `ServiceRole` enum entry in `:api`, pointing `contract` at that interface. Roles are
   derived from the interfaces a service implements, so there is no separate mapping to keep in step
3. Add the use case in `:core/main/domain/usecase/`
5. Add the intent and state fields to `MainStore`
6. Add the UI surface in `:ui-swing`

This is a significant change — open an issue and discuss before starting.
