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

## Windows installation scope

Windows ships one archive and, once Authenticode signing lands, one installer. Do not add a second install scope instead of finishing that one.

| Fact | Value | Where it is defined |
|---|---|---|
| Installer type | MSI, built with WiX 3 from a jpackage app-image | `.github/scripts/package-windows.ps1` |
| Install scope | Per-user (no elevation, no `ALLUSERS`) | `--win-per-user-install` |
| Install directory | `%LOCALAPPDATA%\QTranslate` (`[LocalAppDataFolder]QTranslate`) | jpackage MSI directory table |
| Product registration | `HKCU\Software\QTranslate contributors\QTranslate\<ProductVersion>`, Add/Remove Programs entry in the user's hive (Windows 11 24H2+ surfaces per-user entries from HKLM) | generated MSI tables |
| Start Menu shortcut | `%APPDATA%\Microsoft\Windows\Start Menu\Programs\QTranslate\QTranslate.lnk` | `--win-menu --win-menu-group QTranslate` |
| Upgrade identity | `UpgradeCode` from `.github/windows-upgrade-uuid.txt`; never regenerate it | `--win-upgrade-uuid` |
| Product code | Derived by jpackage from vendor, name and version; changes every version, identical across scopes | jpackage `WinMsiBundler` |
| User data | `%APPDATA%\QTranslate`; the installation directory is never written | `AppDataLayout` |
| Published artifact today | `QTranslate-<version>-windows-x64.zip` only (portable, carries `portable.flag`) | `release.yml` |
| MSI publication | Blocked on Authenticode signing; the unsigned MSI is a CI artifact only | `release.yml`, `ci.yml`, [Development](../docs/Development.md) |

Per-user is the correct scope for this application: it needs no machine-wide resource. It registers no file association and no protocol handler, autostart is the per-user `HKCU` Run key (`WindowsStartupRegistration`), global hotkeys are in-session, and plugins and settings live under `%APPDATA%`. The one cross-user coupling is the single-instance socket on localhost port 49185 (`SingleInstanceGuard`): two sessions on one machine cannot both run the application. That predates the installer and is not something an install scope can fix.

Scope rules:

- Never change the scope of a published product. Windows Installer cannot upgrade a per-machine product with a per-user package that carries the same `UpgradeCode`: the second package is treated as a different installation context instead of a replacement, so the result is a failed install, a duplicate entry, or an orphaned payload. If a scope change ever becomes necessary, the old product must be uninstalled first by a documented step in the release notes.
- Do not ship both a per-user and a per-machine MSI for one version. They share the same `ProductCode`, so only one can be installed and neither upgrades the other.
- Keep `--win-dir-chooser` out. A per-user install has one correct writable location, and a chooser would let a user pick a directory that a later per-user upgrade does not expect.

When signing lands, publishing the installer is a release change, not a packaging change: add the MSI to the published assets and to `SHA256SUMS.txt`, state in the release notes that the MSI installs per-user without administrator rights, and update the README download table. The installer lifecycle is already verified on every candidate (`verify-windows-installer.ps1`): silent install, one registration, per-user data root, in-place upgrade against a synthetic higher version, uninstall, and user data preserved byte-for-byte across both.

## Winget

Winget is an external distribution channel: manifests live in `microsoft/winget-pkgs`, not in this repository, and every new version is a pull request there. Nothing is submitted until a signed MSI is a permanent release asset; a manifest that points at an internal CI artifact cannot be validated and would break for every user after the artifact expires.

The published ZIP is not a winget installer. It carries `portable.flag`, so the application keeps user data next to the launcher, which a package manager's own extraction directory does not survive. Winget must use the MSI, which installs with normal installed semantics.

Manifest set (schema 1.6 or later, produced and validated by `wingetcreate`):

| File | Contents for QTranslate |
|---|---|
| `QTranslate.QTranslate.yaml` | `PackageVersion`, `DefaultLocale: en-US` |
| `QTranslate.QTranslate.installer.yaml` | `InstallerType: msi`, `Scope: user`, `Architecture: x64`, `InstallerUrl`, `InstallerSha256`, `UpgradeBehavior: install`, `InstallerSwitches` silent `/qn /norestart`, `InstallerSuccessCodes: [3010]`, and `AppsAndFeaturesEntries` with `DisplayName: QTranslate`, `Publisher: QTranslate contributors`, `UpgradeCode` from `.github/windows-upgrade-uuid.txt` |
| `QTranslate.QTranslate.locale.en-US.yaml` | `Publisher`, `PackageName`, `License: MPL-2.0`, `ShortDescription`, `Moniker`, tags, repository and issue URLs |

Requirements that follow from the current MSI:

- `PackageIdentifier` is fixed by the first accepted submission. `QTranslate.QTranslate` matches the product name. Winget correlates an installed product with a manifest through Add/Remove Programs, so the manifest `Publisher` and the ARP publisher (`QTranslate contributors`, from `--vendor`) must agree; `AppsAndFeaturesEntries` is the sanctioned way to record the ARP publisher when it differs from the identifier. If moderation objects to the difference, changing `--vendor` to `QTranslate` is the alternative, and it does not affect upgrade identity because the `UpgradeCode` is pinned.
- `PackageVersion` must be the MSI `ProductVersion` that Add/Remove Programs shows. Windows Installer allows only `major.minor.build` (255.255.65535), so a version with a prerelease or build suffix cannot be published. That is also why prereleases are not submitted: two release candidates of one base share a `ProductVersion`.
- `InstallerSha256` and `InstallerUrl` must describe one immutable file. GitHub release asset URLs are stable and a tag is never moved, so they satisfy that; an MSI that is rebuilt or re-uploaded after the manifest is merged does not.
- Because `UpgradeBehavior` is `install` and the MSI carries the pinned `UpgradeCode`, `winget upgrade` replaces the previous version in place rather than installing beside it. The in-place upgrade and the uninstall both keep `%APPDATA%\QTranslate`, so a winget-managed installation keeps the user's history and settings.

Publishing and updating, all outside this repository:

1. Sign the MSI and publish it as a release asset with its `SHA256SUMS.txt` entry.
2. First submission: `wingetcreate new <msi-url>` in a fork of `microsoft/winget-pkgs`, review the generated manifests against the table above, run `winget validate <dir>` and a `winget install --manifest <dir>` test, then open the pull request.
3. Each later release: `wingetcreate update QTranslate.QTranslate --version <version> --urls <msi-url> --submit` and review the pull request it opens.

Known limitation to document for users: the in-application update prompt still offers the Windows ZIP. A user who follows it after installing with winget extracts a second copy instead of upgrading the installed one, and the single-instance guard then makes the second copy exit silently. Pointing the updater at the release page for installed builds is a separate change.
