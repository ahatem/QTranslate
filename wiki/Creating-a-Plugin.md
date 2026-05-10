# Creating a Plugin

Plugins add translation engines, OCR, TTS, spell checkers, or dictionaries to QTranslate — without touching the app. You write a few classes, build a fat JAR, install it through the UI. That's the whole process.

---

## Before you start — read the source

The bundled plugins are better documentation than this guide. Open them before writing a single line:

| Plugin | What it shows |
|--------|--------------|
| [`plugins/google-services/`](../plugins/google-services/src/main/kotlin) | API keys with `@field:Setting`, dynamic language lists, multiple services in one plugin |
| [`plugins/bing-services/`](../plugins/bing-services/src/main/kotlin) | Token auth, chunking long text, static language map |
| [`plugins/common/`](../plugins/common/src/main/kotlin) | HTTP client, JSON parsing, language mapper — copy these freely into your own plugin |

Start with `GooglePlugin.kt` → `GoogleTranslatorService.kt`. That covers 90% of what you need.

---

## What a plugin can provide

A single JAR can include any combination of these:

| Interface | Does |
|-----------|------|
| `Translator` | Translates text from one language to another |
| `TextToSpeech` | Converts text to audio |
| `OCR` | Extracts text from an image |
| `SpellChecker` | Returns spelling corrections |
| `Dictionary` | Definitions, synonyms, examples |
| `Summarizer` | Condenses text into a shorter form, configurable length |
| `Rewriter` | Rewrites text in a different style (Formal, Casual, Concise, Detailed, Simplified) |

---

## Project structure

```
my-plugin/
├── build.gradle.kts
├── settings.gradle.kts
└── src/main/
    ├── kotlin/com/example/myplugin/
    │   ├── MyPlugin.kt
    │   └── MyTranslatorService.kt
    └── resources/
        ├── plugin.json
        ├── assets/icon.svg
        └── META-INF/services/
            └── com.github.ahatem.qtranslate.api.plugin.Plugin
```

---

## Step 1 — Build files

**`settings.gradle.kts`**

```kotlin
rootProject.name = "my-plugin"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://jitpack.io")  // QTranslate API is published here
    }
}
```

**`build.gradle.kts`**

```kotlin
plugins {
    kotlin("jvm") version "2.0.0"
}

group   = "com.example"
version = "1.0.0"

dependencies {
    // compileOnly — available at compile time, NOT bundled in your JAR.
    // QTranslate provides this at runtime. Never use "implementation" here —
    // it causes classloader conflicts and bloats your JAR.
    compileOnly("com.github.ahatem:qtranslate-api:1.0.0")

    // Your own dependencies go as "implementation" — they ARE bundled
    implementation("io.ktor:ktor-client-core:2.3.7")
    implementation("io.ktor:ktor-client-cio:2.3.7")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
}

// Fat JAR — bundles all your "implementation" dependencies into one installable file
tasks.jar {
    manifest {
        attributes["Plugin-Class"] = "com.example.myplugin.MyPlugin"
    }
    from(configurations.runtimeClasspath.get().map {
        if (it.isDirectory) it else zipTree(it)
    })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
```

> **Which API version?** Use the API version, not the app release version — they are independent. Check [`ApiVersion.kt`](../api/src/main/kotlin/com/github/ahatem/qtranslate/api/core/ApiVersion.kt) for the current value. During development before any tagged release exists, you can use a full commit hash instead (e.g. `abc1234`).

---

## Step 2 — The Plugin class

Every plugin has exactly one `Plugin` class. It declares the plugin's identity and returns its services.

**Without configuration:**

```kotlin
package com.example.myplugin

import com.github.ahatem.qtranslate.api.plugin.*

class MyPlugin : Plugin<PluginSettings.None> {
    override val id          = "com.example.my-plugin"  // permanent — never change after publishing
    override val name        = "My Plugin"
    override val version     = "1.0.0"
    override val description = "Translates using the Example API."
    override val icon        = "assets/icon.svg"

    override fun getSettings(): PluginSettings.None = PluginSettings.None

    override fun getServices(): List<Service> = listOf(
        MyTranslatorService()
    )

    override suspend fun initialize(context: PluginContext): Result<Unit, ServiceError> {
        // context.logger  — write to the app log
        // context.scope   — coroutine scope tied to plugin lifecycle; cancelled on disable
        // context.storeValue / getValue — persistent key-value storage, survives restarts
        return Ok(Unit)
    }
}
```

**With API key or settings:**

```kotlin
import com.github.ahatem.qtranslate.api.plugin.PluginSettings
import com.github.ahatem.qtranslate.api.settings.*

@SettingGroups(
    SettingGroup(key = "auth",     title = "Authentication",  order = 10),
    SettingGroup(key = "advanced", title = "Advanced Options", order = 20,
                 collapsible = true, defaultCollapsed = true)
)
class MySettings : PluginSettings.Configurable() {

    @field:Setting(
        label       = "API Key",
        description = "Your key from example.com/developers",
        type        = SettingType.PASSWORD,
        isRequired  = true,
        group       = "auth",
        order       = 10
    )
    var apiKey: String = ""

    @field:Setting(
        label        = "Region",
        type         = SettingType.DROPDOWN,
        options      = "us-east,us-west,eu-central",
        defaultValue = "us-east",
        group        = "auth",
        order        = 20
    )
    var region: String = "us-east"

    @field:Setting(
        label        = "Request timeout (s)",
        type         = SettingType.SLIDER,
        minValue     = 5.0,
        maxValue     = 60.0,
        step         = 5.0,
        defaultValue = "30",
        group        = "advanced",
        order        = 10
    )
    var timeoutSeconds: Double = 30.0

    @field:Setting(
        label        = "Enable response cache",
        type         = SettingType.BOOLEAN,
        defaultValue = "false",
        group        = "advanced",
        order        = 20
    )
    var cacheEnabled: Boolean = false

    @PluginAction(label = "Test Connection", group = "auth", tooltip = "Verifies the API key is valid.")
    suspend fun testConnection(): String? {
        if (apiKey.isBlank()) return "No API key configured."
        // Make a lightweight test request here
        return "Connected successfully."
    }
}

class MyPlugin : Plugin<MySettings> {
    private val settings = MySettings()

    override val id      = "com.example.my-plugin"
    override val name    = "My Plugin"
    override val version = "1.0.0"

    override fun getSettings() = settings
    override fun getServices() = listOf(MyTranslatorService(settings))

    override suspend fun initialize(context: PluginContext): Result<Unit, ServiceError> {
        return Ok(Unit)
    }
}
```

`@field:Setting` on a `var` property is enough — QTranslate builds the settings dialog automatically. No UI code needed.

**Setting types** (`SettingType`):

| Type | Renders as |
|------|-----------|
| `TEXT` | Single-line text field (default) |
| `PASSWORD` | Masked text field |
| `TEXTAREA` | Multi-line text area |
| `BOOLEAN` | Checkbox |
| `DROPDOWN` | Drop-down from `options` list |
| `NUMBER` | Numeric spinner |
| `SLIDER` | Slider with `minValue`/`maxValue`/`step` |
| `FILE_PATH` | Text field + file chooser button |

**Grouping** — add `@SettingGroups(...)` to the settings class and reference the group `key` in each `@field:Setting`. Groups with `collapsible = true` can be collapsed by the user.

**Actions** — annotate a `suspend fun methodName(): String?` in your settings class with `@PluginAction`. QTranslate renders it as a button in the group's action strip. Return a non-null string to show a result message.

**Conditional visibility** — use `showIf = "propertyName=value"` to show a field only when another field has a specific value.

---

## Step 3 — Implement a service

### Translator

```kotlin
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.translator.*
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.Ok   // Ok(...) and Err(...) are top-level functions,
import com.github.michaelbull.result.Err  // not types — import them like this
import java.io.IOException

class MyTranslatorService(
    private val settings: MySettings
) : Translator {
    override val id                 = "com.example.my-plugin.translator"
    override val name               = "My Translator"
    override val version            = "1.0.0"
    override val iconPath           = "assets/icon.svg"
    override val supportedLanguages = SupportedLanguages.All
    // SupportedLanguages.Specific(setOf(LanguageCode("en"), LanguageCode("ar")))
    // SupportedLanguages.Dynamic  → override fetchSupportedLanguages() below

    override suspend fun translate(
        request: TranslationRequest
    ): Result<TranslationResponse, ServiceError> {

        if (settings.apiKey.isBlank())
            return Err(ServiceError.AuthError("API key not configured"))

        return try {
            val text = callMyApi(
                text   = request.text,
                from   = request.sourceLanguage.tag,
                to     = request.targetLanguage.tag,
                apiKey = settings.apiKey
            )
            Ok(TranslationResponse(translatedText = text))
        } catch (e: IOException) {
            Err(ServiceError.NetworkError("Request failed: ${e.message}", e))
        } catch (e: Exception) {
            Err(ServiceError.UnknownError("Unexpected error: ${e.message}", e))
        }
    }

    // Only implement this when supportedLanguages = SupportedLanguages.Dynamic
    override suspend fun fetchSupportedLanguages(): Result<Set<LanguageCode>, ServiceError> {
        return try {
            val codes = fetchLanguagesFromApi(settings.apiKey)
            Ok(codes.map { LanguageCode(it) }.toSet())
        } catch (e: Exception) {
            Err(ServiceError.NetworkError("Could not fetch language list", e))
        }
    }
}
```

**Return errors, never throw them.** Use:

| Error type | When |
|-----------|------|
| `ServiceError.NetworkError` | Connectivity, timeout, DNS |
| `ServiceError.AuthError` | Invalid or missing API key |
| `ServiceError.RateLimitError` | Quota exceeded |
| `ServiceError.InvalidResponseError` | API returned unexpected format |
| `ServiceError.UnknownError` | Anything else |

**Other service types** (`TextToSpeech`, `OCR`, `SpellChecker`, `Dictionary`) follow the exact same pattern — implement the interface, return `Ok`/`Err`. See the Google plugin for a complete TTS and OCR example.

---

## Step 4 — plugin.json

`src/main/resources/plugin.json`:

```json
{
  "id":            "com.example.my-plugin",
  "name":          "My Plugin",
  "version":       "1.0.0",
  "author":        "Your Name",
  "description":   "Translates using the Example API.",
  "minApiVersion": "1.0.0",
  "icon":          "assets/icon.svg"
}
```

- `id` must match your `Plugin` class exactly — this is your plugin's permanent identifier
- `minApiVersion` — use `1.0.0` unless you know you need something newer
- `icon` — path inside your JAR resources

---

## Step 5 — ServiceLoader registration

Create this file (the filename is the full interface name):

```
src/main/resources/META-INF/services/com.github.ahatem.qtranslate.api.plugin.Plugin
```

Contents — just your Plugin class fully qualified:

```
com.example.myplugin.MyPlugin
```

This is how QTranslate finds your plugin. No reflection, no scanning, just this one file.

---

## Step 6 — Build and install

```bash
./gradlew jar
```

JAR is at `build/libs/my-plugin-1.0.0.jar`.

**Install:**
1. Settings → Plugins → Install Plugin → select the JAR
2. Enable the plugin
3. Configure it if needed (Settings → Plugins → select → Configure)
4. Assign it in Settings → Services & Presets

**Faster dev loop** — skip the install UI, copy directly:

```bash
# Windows
./gradlew jar && copy build\libs\my-plugin-1.0.0.jar "C:\path\to\appdata\plugins\"
```

Then restart QTranslate. Much faster than reinstalling through the UI every time.

---

## Tips

**`PluginContext`** — `onInitialize` gives you everything you need:
- `context.logger` — writes to the app log file, visible in dev mode
- `context.scope` — coroutine scope, cancelled when the plugin is disabled
- `context.storeValue(key, value)` / `context.getValue(key)` — persistent per-plugin storage

**Never import `:core` or `:ui-swing`** — your plugin must only depend on `:api`. If you need something that isn't in the API, open an issue.

**Icons** — SVG preferred, ~32×32. FlatLaf applies a colour filter when rendering icons in the plugin list — if you want your icon to adapt to dark/light themes automatically, use a single-colour SVG. If you provide a full-colour icon it will display as-is without filtering.

**Don't log credentials** — never log `settings.apiKey` or any secret. The log is visible to users.

**Coroutines** — service methods are `suspend` and run on `Dispatchers.IO`. Don't block the thread.

---

## Troubleshooting

**Plugin shows as "Failed"**

Read the error in the plugin detail panel:
- `No Plugin implementation found via ServiceLoader` → your `META-INF/services/` file is missing or has a typo in the class name
- `plugin.json is missing or could not be parsed` → file not at `src/main/resources/plugin.json`, or invalid JSON
- `API version incompatible` → your `minApiVersion` in `plugin.json` is newer than what the app supports, or the MAJOR version doesn't match. Check [`ApiVersion.kt`](../api/src/main/kotlin/com/github/ahatem/qtranslate/api/core/ApiVersion.kt) for the current API version and set `minApiVersion` to match

**Services don't appear in Settings → Services & Presets**

The plugin is enabled but the service isn't assigned. Open Settings → Services & Presets and select it from the dropdown.

**`ClassNotFoundException` when the plugin runs**

A class from one of your dependencies isn't in the JAR. This means the fat JAR task isn't picking up everything.

Check what's actually inside your JAR:
```bash
jar tf build/libs/my-plugin-1.0.0.jar
```
You should see `.class` files from your dependencies (e.g. `io/ktor/...`). If you only see your own classes, the fat JAR task isn't working.

Make sure your `tasks.jar` block includes `runtimeClasspath` — that's the configuration that holds all your `implementation` dependencies:
```kotlin
from(configurations.runtimeClasspath.get().map {
    if (it.isDirectory) it else zipTree(it)
})
```
Also check that your dependencies are declared as `implementation`, not `compileOnly`. Anything `compileOnly` is intentionally excluded from the JAR.

**Settings dialog doesn't open**

Your settings class must extend `PluginSettings.Configurable` and the annotated properties must be `var`, not `val`.

**`Unresolved reference: Ok` / `Unresolved reference: Err`**

`Ok` and `Err` are top-level functions from the `kotlin-result` library — they are provided transitively by the API dependency so you don't need to add `kotlin-result` to your own `build.gradle.kts`. You just need the imports:

```kotlin
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Result  // for the return type
```

---

## Publishing on GitHub

Once your plugin works, publishing it on GitHub makes it discoverable in QTranslate's built-in marketplace.

### Step 1 — Create a public GitHub repository

Any name works, but `qtranslate-{plugin-id}` is the convention:

```
github.com/your-username/qtranslate-my-plugin
```

### Step 2 — Add the `qtranslate-plugin` topic

In your repo's **Settings → Topics**, add `qtranslate-plugin`. This is what the QTranslate marketplace uses to discover plugins — without this tag, your plugin won't appear.

### Step 3 — Add `qtranslate-plugin.json` to your repo root

This file provides the metadata shown in the marketplace:

```json
{
  "id":            "com.example.my-plugin",
  "name":          "My Plugin",
  "version":       "1.0.0",
  "author":        "your-github-username",
  "description":   "One-sentence description shown in the marketplace.",
  "serviceTypes":  ["TRANSLATOR"],
  "minApiVersion": "1.0.0",
  "sha256":        "abc123...",
  "icon":          "assets/icon.svg"
}
```

**Notes:**
- `id` must match the `id` in your `Plugin` class and `plugin.json` exactly
- `serviceTypes`: one or more of `TRANSLATOR` `TTS` `OCR` `SPELL_CHECKER` `DICTIONARY` `SUMMARIZER` `REWRITER`
- `sha256`: optional but strongly recommended — the SHA-256 hash of the release JAR. Users see a warning if it is absent. Generate it with `sha256sum my-plugin.jar` (Linux/macOS) or `certutil -hashfile my-plugin.jar SHA256` (Windows).
- `icon`: path to an SVG inside your repo (not your JAR). Used as the marketplace thumbnail.

### Step 4 — Create a GitHub Release with the JAR as an asset

The marketplace downloads the **first `.jar` file** found in your latest release's assets. Name it `{plugin-id}.jar` so the filename is predictable.

Here is a full `release.yml` GitHub Actions workflow you can paste into `.github/workflows/release.yml`:

```yaml
name: Release

on:
  push:
    tags:
      - 'v*.*.*'

permissions:
  contents: write

jobs:
  release:
    runs-on: ubuntu-latest

    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'

      - uses: gradle/actions/setup-gradle@v4

      - name: Extract version
        id: version
        run: echo "version=${GITHUB_REF_NAME#v}" >> "$GITHUB_OUTPUT"

      - name: Build fat JAR
        run: ./gradlew shadowJar --no-daemon
        env:
          PLUGIN_VERSION: ${{ steps.version.outputs.version }}

      - name: Compute SHA-256
        id: sha256
        run: |
          JAR=$(ls build/libs/my-plugin-*.jar | head -1)
          echo "hash=$(sha256sum $JAR | awk '{print $1}')" >> "$GITHUB_OUTPUT"
          echo "jar=$JAR" >> "$GITHUB_OUTPUT"

      - name: Create GitHub Release
        uses: softprops/action-gh-release@v2
        with:
          name: My Plugin ${{ steps.version.outputs.version }}
          body: |
            SHA-256: `${{ steps.sha256.outputs.hash }}`
          files: ${{ steps.sha256.outputs.jar }}
```

> **Tip:** After each release, copy the computed `sha256` value into `qtranslate-plugin.json` and commit it so the marketplace can verify the download.

### Step 5 — Add README badges (optional)

```markdown
[![Latest Release](https://img.shields.io/github/v/release/your-username/qtranslate-my-plugin?style=flat-square)](https://github.com/your-username/qtranslate-my-plugin/releases/latest)
[![QTranslate Plugin](https://img.shields.io/badge/QTranslate-plugin-4A90D9?style=flat-square)](https://github.com/ahatem/qtranslate)
[![License](https://img.shields.io/github/license/your-username/qtranslate-my-plugin?style=flat-square)](LICENSE)
```

---

Your plugin will appear in QTranslate's marketplace automatically once it has the `qtranslate-plugin` topic. Users can browse, install, and update it without leaving the app.