# Releasing QTranslate

This is the maintainer procedure for a future release. A tag matching `v*.*.*` starts `.github/workflows/release.yml` and publishes a non-draft GitHub Release. Finish candidate validation before pushing a tag.

## Prepare an exact candidate

1. Choose a Semantic Versioning value, such as `X.Y.Z` or `X.Y.Z-rc.1`. Use the real numeric version in commands below; omit `v` except in the Git tag. Prepare the release changelog in a separate release-preparation PR.
2. Set `AppConstants.APP_VERSION` in `core/src/main/kotlin/com/github/ahatem/qtranslate/core/shared/AppConstants.kt` to **exactly** that value. About, startup logging, and the updater read this constant. For an RC, retain the full suffix in the running app. The updater must offer the later stable `X.Y.Z` to an installation running `X.Y.Z-rc.1`; a stable installation must not be offered a same-base prerelease.
3. Merge intended changes into `develop`, pull it, and confirm `git status --short` is empty. Open a focused release-preparation PR against `develop` and wait for all required checks.
4. From the exact candidate commit, run (substitute the chosen version):

```powershell
.\gradlew.bat clean build --no-daemon
.\gradlew.bat smokeTestAllPlugins --no-daemon --console=plain
.\gradlew.bat validateReleaseVersion "-PreleaseVersion=X.Y.Z" --no-daemon
.\gradlew.bat assembleReleaseVariants "-PreleaseVersion=X.Y.Z" --no-daemon
.\gradlew.bat verifyReleaseArtifacts "-PreleaseVersion=X.Y.Z" --no-daemon
```

Quote `-PreleaseVersion=...` in PowerShell. The version validation fails if the artifact version differs from the compiled About/updater identity. The assembly includes app-only, portable, and standalone plugin JARs, release size budgets, class-version checks, metadata, and SHA-256 generation. The verifier compares exact artifact and plugin inventories, metadata hashes, archive resources, and every `SHA256SUMS.txt` entry against the files. Inspect `build/plugin-smoke-test/report.txt`, `build/release/SIZE_REPORT.md`, `build/release/release-metadata.json`, and `build/release/SHA256SUMS.txt` as candidate evidence.

The complete platform evidence comes from PR CI on the same candidate commit:

- Linux extracts the real portable ZIP and probes it offline with Java 17 and Java 21. The bounded readiness probe checks packaged plugin discovery, initialization, JLayer, and resource inventory.
- macOS extracts the same portable ZIP, runs readiness, and checks both arm64 and x86_64 slices of the packaged Vision and spell helpers.
- Windows packages that portable ZIP using `.github/scripts/package-windows.ps1`, the same recipe as the tag workflow. Download the `qtranslate-windows-candidate` PR artifact. Its verifier checks package inventory, bundled Java, plugin readiness through bundled Java, and `QTranslate.exe --release-probe` with `JAVA_HOME` cleared. Both probes are bounded and exit without opening the UI.
- The `Verify complete release inventory` CI job assembles all variants, includes the Windows ZIP, checks metadata and size results, and rehashes every release artifact. The tag workflow repeats the Windows package verifier and full inventory verification before its publish step.

Do not tag until code/tests, plugin smoke, release variants, class-version checks, size budgets, Linux Java 17/21, Windows candidate, macOS candidate and universal helpers, metadata, checksums, exact version alignment, and required manual QA are green. A PR candidate is the first Windows package test, not a prerelease tag.

## Manual QA

Run `.\gradlew.bat runWithPlugins` on a supported desktop, and test the extracted candidate on Windows by launching `QTranslate.exe` without depending on `JAVA_HOME`. Confirm a second instance hands focus to the first and exits, then restart to check settings persistence. Cover:

- QTranslate Light and Dark, OS synchronization, and a custom theme; narrow and wide service selectors; LTR and RTL visual quality, especially English and Arabic.
- Google, Bing, Mozhi, MyMemory, Reverso, Yandex, and DeepL translation where available; actionable errors for unavailable endpoints, rate limits, and missing credentials.
- TTS, OCR, spell check, dictionary, Quick Translate, global hotkeys, tray, and history.
- DOCX, PDF, TXT, SRT, and VTT translation; cancellation and responsiveness during slow requests and large documents.
- Plugin search, enable/disable, configuration, installation from file, and failed-plugin display; actual download paths.

The automated readiness probe is deliberately offline and does not replace these desktop and service checks. Unofficial web endpoints may be unavailable; confirm they fail gracefully and supported core services remain usable.

## Publish after candidate approval

1. Merge the release-preparation PR into `develop`, then merge `develop` into `main`. Pull `main` and confirm its clean HEAD is the tested commit. Do not tag an unmerged feature branch.
2. Create and push an annotated `vX.Y.Z` tag (or `vX.Y.Z-rc.1` for a prerelease) on that commit. The tag workflow verifies exact runtime/tag version identity. RC, beta, and alpha tags are marked as GitHub prereleases.
3. Wait for **every Release workflow job** to pass. The Windows package job validates the bundled runtime and launcher before the publish job can run. The publish job verifies full artifact inventory and checksums before creating the Release.
4. Confirm the GitHub Release contains the app-only JAR, portable ZIP, individual plugin JARs, Windows x64 bundled-runtime ZIP, `release-metadata.json`, `SIZE_REPORT.md`, and `SHA256SUMS.txt`. Download the published artifacts and verify checksums with `sha256sum -c SHA256SUMS.txt` in the directory containing all assets.
5. Run the downloaded Windows and portable builds. Check About shows the tag version, an older stable install sees the new stable release, and a fresh stable install does not offer itself as an update. For an RC, check that a later stable release is offered to the RC installation. Check release notes and changelog links.

If published verification fails, fix the issue and use a new version or prerelease number; do not silently move a published tag. After publishing, reconcile `main` back into `develop` if necessary and retain the smoke and workflow evidence.
