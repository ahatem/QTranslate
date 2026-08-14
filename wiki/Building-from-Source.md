# Building from Source

This guide walks you through compiling QTranslate from source and running it locally.

---

## Requirements

| Tool | Version | Notes |
|------|---------|-------|
| Java | 11+ (to run) | [Temurin](https://adoptium.net) recommended |
| Git | any | |
| IntelliJ IDEA | 2023+ | Optional but recommended |

You do not need to install Gradle — the project includes a Gradle wrapper (`./gradlew`).

> **To build from source**, the Gradle build uses `jvmToolchain(21)` for compilation. Gradle will automatically download and use Java 21 via its toolchain provisioning — you do not need to install it manually. The compiled JAR targets Java 11 bytecode, so the final `QTranslate.jar` runs on any Java 11 or later installation.

---

## Clone the repository

```bash
git clone https://github.com/ahatem/qtranslate.git
cd qtranslate
```

If you want to contribute, fork first and clone your fork instead.

---

## Build all modules

```bash
# macOS / Linux
./gradlew build

# Windows
gradlew.bat build
```

This compiles `:api`, `:core`, `:ui-swing`, `:app`, and the bundled plugins. On first run it downloads dependencies, which may take a minute.

---

## Run the application

For ordinary development with the app module only:

```bash
./gradlew :app:run -DappData="C:/Users/you/QTranslateData"
```

Replace the path with wherever you want QTranslate to store its data (plugins, settings, history). The directory will be created if it does not exist.

On macOS/Linux:
```bash
./gradlew :app:run -DappData="/home/you/QTranslateData"
```

For release-style manual QA with every bundled plugin staged and loaded:

```bash
# macOS / Linux
./gradlew runWithPlugins

# Windows
gradlew.bat runWithPlugins
```

The QA launcher uses an isolated directory under `app/build/manual-qa/`, prints plugin load failures to the console, and leaves your normal QTranslate settings untouched.

---

## Build release artifacts

```bash
./gradlew assembleReleaseVariants -PreleaseVersion=1.3.0
```

On PowerShell, quote the property — unquoted, PowerShell splits it at the first dot
and Gradle fails with `Task '.3.0' not found`:

```powershell
.\gradlew.bat assembleReleaseVariants "-PreleaseVersion=1.3.0"
```

Artifacts are written to `build/release/`:

- `QTranslate-App-1.3.0.jar` — app only
- `QTranslate-Minimal-1.3.0.zip` — app with Google, Bing, and Mozhi
- `QTranslate-Full-1.3.0.zip` — app with every bundled plugin
- `plugins/*.jar` — independently versioned plugin artifacts
- `release-metadata.json`, `SIZE_REPORT.md`, and `SHA256SUMS.txt`

Replace `1.3.0` with the version you are testing. See [Releasing](Releasing.md) for the complete verification and publishing process.

---

## Build a plugin

Each plugin is a separate Gradle subproject under `plugins/`:

```bash
./gradlew :plugins:google-services:shadowJar
./gradlew assembleIndividualPlugins -PreleaseVersion=1.3.0   # quote on PowerShell
```

Single-plugin JARs are written to `plugins/<name>/build/libs/`. The aggregate task writes normalized, versioned JARs to `build/release/plugins/`. Install them through the QTranslate UI as described in [Installing Plugins](Installing-Plugins.md).

---

## Module structure

```
qtranslate/
  api/           ← interfaces, no implementations (plugins depend on this)
  core/          ← business logic, MVI stores, use cases, repositories
  ui-swing/      ← Swing UI components
  app/           ← composition root, main entry point
  plugins/
    common/      ← shared HTTP client, language mapper, JSON utilities
    google-services/
    bing-services/
    ai-services/ ← OpenRouter-based translator, summarizer, rewriter, OCR, dictionary
    deepl-services/
    mozhi-services/
    mymemory-services/
    libretranslate-services/
    reverso-services/
    yandex-web-services/
    wikimedia-reference/
  buildSrc/      ← shared Gradle convention plugins
```

---

## IntelliJ IDEA setup

1. **File → Open** and select the `qtranslate` directory
2. Wait for Gradle sync to complete
3. Create a Run Configuration:
   - Type: **Gradle**
   - Gradle project: `qtranslate`
   - Tasks: `runWithPlugins` for full manual QA, or `:app:run` for app-only development
   - VM options for `:app:run`: `-DappData=C:/path/to/your/test/data`

---

## Troubleshooting

**`java.lang.UnsupportedClassVersionError`**
Your Java version is too old. Run `java -version` and make sure it's 11 or later.

**Build fails on `ui-swing`**
Make sure you have internet access — FlatLaf and other dependencies are downloaded from Maven Central on first build. If you are behind a proxy, configure it in `~/.gradle/gradle.properties`.

**`There are multiple DataStores active for the same file`**
You have two run configurations pointing at the same `appData` directory. Use a different directory per configuration, or stop the first instance before starting the second.
