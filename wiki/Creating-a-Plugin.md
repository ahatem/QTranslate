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

    override fun onInitialize(context: PluginContext) {
        // context.logger  — write to the app log
        // context.scope   — coroutine scope tied to plugin lifecycle
        // context.storeValue / getValue — persistent storage, survives restarts
    }
}
```

**With API key or settings:**

```kotlin
import com.github.ahatem.qtranslate.api.plugin.PluginSettings
import com.github.ahatem.qtranslate.api.plugin.Setting

class MySettings : PluginSettings.Configurable() {
    @field:Setting(
        label       = "API Key",
        description = "Your key from example.com/developers",
        isRequired  = true
    )
    var apiKey: String = ""

    @field:Setting(label = "Region")
    var region: String = "us-east"
}

class MyPlugin : Plugin<MySettings> {
    private val settings = MySettings()

    override val id      = "com.example.my-plugin"
    override val name    = "My Plugin"
    override val version = "1.0.0"

    override fun getSettings() = settings
    override fun getServices() = listOf(MyTranslatorService(settings))
}
```

`@field:Setting` on a `var` property is enough — QTranslate builds the settings dialog automatically. No UI code needed.

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

## Share your plugin

Once it works: [submit to the community list](https://github.com/ahatem/qtranslate/issues/new?template=plugin_submission.md) — it gets added to the README and wiki.