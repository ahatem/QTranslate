# Building from Source

This guide walks you through compiling QTranslate from source and running it locally.

---

## Requirements

| Tool | Version | Notes |
|------|---------|-------|
| Java | 11+ | [Temurin](https://adoptium.net) recommended |
| Git | any | |
| IntelliJ IDEA | 2023+ | Optional but recommended |

You do not need to install Gradle — the project includes a Gradle wrapper (`./gradlew`).

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
./gradlew build
```

This compiles `:api`, `:core`, `:ui-swing`, `:app`, and the bundled plugins. On first run it downloads dependencies, which may take a minute.

---

## Run the application

```bash
./gradlew :app:run -DappData="C:/Users/you/QTranslateData"
```

Replace the path with wherever you want QTranslate to store its data (plugins, settings, history). The directory will be created if it does not exist.

On macOS/Linux:
```bash
./gradlew :app:run -DappData="/home/you/QTranslateData"
```

---

## Build a fat JAR

```bash
./gradlew :app:jar
```

The JAR is written to `app/build/libs/`. Run it with:

```bash
java -jar app/build/libs/app.jar
```

---

## Build a plugin

Each plugin is a separate Gradle subproject under `plugins/`:

```bash
./gradlew :plugins:google-services:jar
./gradlew :plugins:bing-services:jar
```

The plugin JARs are written to `plugins/<name>/build/libs/`. Install them through the QTranslate UI as described in [Installing Plugins](Installing-Plugins.md).

---

## Module structure

```
qtranslate/
  api/           ← interfaces, no implementations (plugins depend on this)
  core/          ← business logic, MVI stores, use cases, repositories
  ui-swing/      ← Swing UI components
  app/           ← composition root, main entry point
  plugins/
    common/      ← shared utilities for plugin authors
    google-services/
    bing-services/
  buildSrc/      ← shared Gradle convention plugins
```

---

## IntelliJ IDEA setup

1. **File → Open** and select the `qtranslate` directory
2. Wait for Gradle sync to complete
3. Create a Run Configuration:
   - Type: **Gradle**
   - Gradle project: `qtranslate`
   - Tasks: `:app:run`
   - VM options: `-DappData=C:/path/to/your/test/data`

---

## Troubleshooting

**`java.lang.UnsupportedClassVersionError`**
Your Java version is too old. Run `java -version` and make sure it's 11 or later.

**Build fails on `ui-swing`**
This module requires the FlatLaf dependencies from `sonatype-snapshots`. Make sure you have internet access and the snapshot repos are reachable (listed in `ui-swing/build.gradle.kts`).

**`There are multiple DataStores active for the same file`**
You have two run configurations pointing at the same `appData` directory. Use a different directory per configuration, or stop the first instance before starting the second.
