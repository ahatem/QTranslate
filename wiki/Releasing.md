# Releasing QTranslate

This guide is for maintainers preparing and publishing an official QTranslate release.

QTranslate releases are tag-driven. Pushing a tag matching `v*.*.*` starts the Release workflow and publishes a non-draft GitHub Release. Test the exact candidate commit before pushing the tag.

## Choose a version

QTranslate follows Semantic Versioning:

- Patch (`1.2.1` to `1.2.2`): compatible bug fixes
- Minor (`1.2.1` to `1.3.0`): backward-compatible features
- Major (`1.x` to `2.0.0`): incompatible app or plugin API changes
- Prerelease (`1.3.0-rc.1`): public candidate; the workflow marks `alpha`, `beta`, and `rc` tags as prereleases

Use the same version everywhere below, without `v` for Gradle and with `v` for the Git tag.

## Test a release candidate

### 1. Prepare the candidate branch

1. Merge all intended pull requests into `develop`.
2. Pull the latest remote state and confirm the tree is clean:

```powershell
git switch develop
git pull --ff-only origin develop
git status --short
```

3. Update `CHANGELOG.md`: move the relevant entries from `Unreleased` into a versioned section such as `## [1.3.0] - 2026-08-14`, then update the comparison links at the bottom.
4. Open a focused release-preparation PR against `develop` and let all required checks pass.

### 2. Run automated verification

From the exact commit you intend to tag:

```powershell
.\gradlew.bat clean build test --no-daemon
.\gradlew.bat smokeTestAllPlugins --no-daemon --console=plain
.\gradlew.bat assembleReleaseVariants "-PreleaseVersion=1.3.0" --no-daemon
```

> Quote `-PreleaseVersion=...` in PowerShell. Unquoted, PowerShell splits the argument
> at the first dot and Gradle fails with `Task '.3.0' not found`.

Review:

- `build/plugin-smoke-test/report.txt`
- `build/release/SIZE_REPORT.md`
- `build/release/release-metadata.json`
- `build/release/SHA256SUMS.txt`

All tests must pass, plugin failures must be understood, and no artifact may exceed its configured size budget.

### 3. Verify checksums and archives

On Windows PowerShell:

```powershell
Get-Content build/release/SHA256SUMS.txt
Get-FileHash build/release/QTranslate-1.3.0.zip -Algorithm SHA256
Get-FileHash build/release/QTranslate-App-1.3.0.jar -Algorithm SHA256
```

Compare each hash with `SHA256SUMS.txt`. Open the portable ZIP and confirm it contains:

- `QTranslate/QTranslate.jar`
- `QTranslate/languages/`
- `QTranslate/themes/`
- Every bundled plugin JAR under `QTranslate/plugins/`

Confirm each JAR under `build/release/plugins/` has a matching entry in `release-metadata.json` with the expected plugin ID, version, and minimum API version.

### 4. Test supported runtime paths

Use fresh temporary folders so existing settings do not hide first-run problems.

1. **Java 11 portable test:** run the portable ZIP using a Java 11 runtime and `java -jar QTranslate.jar`.
2. **Current Java portable test:** repeat with Java 17 or 21.
3. **App-only test:** run `QTranslate-App-1.3.0.jar`, confirm the empty plugin state is clear, then install one individual plugin JAR from `build/release/plugins/`.
4. **Windows package test:** download the Windows artifact produced by a prerelease tag or workflow run, extract it on a machine without relying on `JAVA_HOME`, and run `QTranslate.exe`.
5. Confirm a second app instance is rejected cleanly.
6. Restart after changing settings and confirm they persist.

### 5. Manual UI and service checklist

Run the complete manual QA build:

```powershell
.\gradlew.bat runWithPlugins
```

Check at minimum:

- QTranslate Light, QTranslate Dark, OS synchronization, and one custom theme
- Classic and enhanced service selectors at narrow and wide window sizes
- Translation with Google, Bing, Mozhi, MyMemory, Reverso, Yandex Web, and DeepL where available
- Clear rate-limit, network, and authentication errors for unavailable services
- DeepL official-key mode and no-key fallback mode
- Mozhi endpoint selection and connection tests
- Wikipedia and Wiktionary lookups
- TTS, OCR, spell checking, dictionary, Quick Translate, global hotkeys, tray behavior, and history
- DOCX structure preservation, PDF best-effort mode, TXT, SRT, and VTT translation
- Plugin search/filter, enable/disable, configure, install from file, drag-and-drop install, and failed-plugin display
- LTR and RTL layouts, especially English and Arabic
- Cancellation and app responsiveness during slow network and large-document operations

Unofficial web services may be unavailable during a release test. The release can proceed only if they fail gracefully with actionable messages and at least the supported core services remain usable.

## Publish the release

### 1. Merge to main

After the candidate passes and the release-preparation PR is merged into `develop`, open and merge a PR from `develop` into `main`. Do not tag an unmerged feature branch.

Verify the local `main` commit matches the remote commit you intend to release:

```powershell
git switch main
git pull --ff-only origin main
git status --short
git log -1 --oneline
```

### 2. Create and push the tag

Use an annotated tag:

```powershell
git tag -a v1.3.0 -m "QTranslate 1.3.0"
git show v1.3.0 --no-patch
git push origin v1.3.0
```

The tag starts `.github/workflows/release.yml`. It builds:

- App-only portable JAR
- Portable ZIP with every bundled plugin
- Individual versioned plugin JARs
- Windows x64 app image with bundled runtime
- Release metadata, size report, and SHA-256 checksums

Prerelease tags containing `-alpha`, `-beta`, or `-rc` are marked as GitHub prereleases automatically.

### 3. Verify the published release

1. Wait for both Release workflow jobs to pass.
2. Open the GitHub Release and confirm every expected asset is present.
3. Download the assets from GitHub, not from the local build, and verify their checksums.
4. Run the downloaded Windows package and one portable ZIP.
5. Confirm the in-app updater sees the new version from an older installation.
6. Check that release notes and the Full changelog link render correctly.

If verification fails, do not move or reuse the tag. Fix the issue, increment the version (or prerelease number), and publish a new tag. Delete a published release or tag only when absolutely necessary and communicate why, because users may already have downloaded it.

## After publishing

1. Merge `main` back into `develop` if the release PR or post-release edits created any divergence.
2. Confirm `CHANGELOG.md` has a fresh empty `Unreleased` section.
3. Announce known limitations, especially unofficial endpoint availability.
4. Keep the plugin smoke report and release workflow logs for troubleshooting.
