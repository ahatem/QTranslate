# Creating a Plugin

QTranslate's plugin system lets you add any translation engine, OCR service, or TTS provider without touching the core application. Plugins are plain Kotlin or Java JARs — you write a few classes, build a fat JAR, and install it like any other plugin.

---

## Start here — read the real implementations first

Before reading this guide, open the bundled plugin source code. It is the most honest documentation that exists — real working code that handles all the edge cases this guide might not mention.

| Folder | What to read first | Why it's useful |
|--------|-------------------|-----------------|
| [`plugins/google-services/`](../plugins/google-services/src/main/kotlin/com/github/ahatem/qtranslate/plugins/google) | `GooglePlugin.kt` then `GoogleTranslatorService.kt` | Shows `PluginSettings.Configurable`, `@field:Setting` for API keys, `SupportedLanguages.Dynamic`, and `fetchSupportedLanguages()` |
| [`plugins/bing-services/`](../plugins/bing-services/src/main/kotlin/com/github/ahatem/qtranslate/plugins/bing) | `BingPlugin.kt` then `BingTranslatorService.kt` | Shows `PluginSettings.None`, token-based auth, request chunking for long text, and a full language mapper |
| [`plugins/common/`](../plugins/common/src/main/kotlin/com/github/ahatem/qtranslate/plugins/common) | `KtorHttpClient.kt`, `LanguageMapper.kt` | Reusable base classes you can copy directly into your own plugin |

The `plugins/common` module is particularly useful — it contains the HTTP client wrapper, JSON parser, and language mapper base class that both bundled plugins use. You are welcome to use the same patterns.

---

## What can a plugin provide?

A single plugin JAR can provide any combination of these service types:

| Interface | What it does |
|-----------|-------------|
| `Translator` | Translates text between two languages |
| `TextToSpeech` | Converts text to audio |
| `OCR` | Extracts text from an image |
| `SpellChecker` | Returns spelling corrections for a string |
| `Dictionary` | Returns definitions, synonyms, examples |

---

## Prerequisites

- Java 11+
- Kotlin 1.9+ (or Java — the API is pure JVM)
- Gradle or Maven for building

---

## Step 1 — Add the API dependency

The `:api` module contains all the interfaces your plugin needs. It is published to... *(coming soon — for now, build it locally and publish to mavenLocal)*.

```kotlin
// build.gradle.kts
dependencies {
    compileOnly("com.github.ahatem:qtranslate-api:1.0.0")
}
```

Add a fat JAR task so all your dependencies are bundled:

```kotlin
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

---

## Step 2 — Create the Plugin class

```kotlin
class MyPlugin : Plugin<PluginSettings.None> {
    override val id          = "com.example.my-plugin"   // stable, reverse-domain style
    override val name        = "My Plugin"
    override val version     = "1.0.0"
    override val description = "Translates text using the Example API."

    override fun getSettings() = PluginSettings.None

    override fun getServices(): List<Service> = listOf(
        MyTranslatorService()
    )

    override fun onInitialize(context: PluginContext) {
        context.logger.info("My plugin initialised")
    }
}
```

**If your plugin needs user configuration** (API keys, region, etc.), extend `PluginSettings.Configurable` instead:

```kotlin
class MySettings : PluginSettings.Configurable() {
    @field:Setting(label = "API Key", description = "Your Example API key", isRequired = true)
    var apiKey: String = ""
}

class MyPlugin : Plugin<MySettings> {
    private val settings = MySettings()
    override fun getSettings() = settings
    // ...
}
```

The `@field:Setting` annotation causes QTranslate to automatically build a configuration dialog for your plugin — no UI code needed.

---

## Step 3 — Implement a service

Here is a minimal `Translator` implementation:

```kotlin
class MyTranslatorService : Translator {
    override val id                = "com.example.my-plugin-translate"
    override val name              = "My Translator"
    override val version           = "1.0.0"
    override val iconPath          = "assets/icon.svg"   // relative to JAR resources
    override val supportedLanguages = SupportedLanguages.All

    override suspend fun translate(request: TranslationRequest)
            : Result<TranslationResponse, ServiceError> {
        // Call your API here
        val translated = callMyApi(request.text, request.targetLanguage.tag)
        return Ok(TranslationResponse(translatedText = translated))
    }
}
```

**Error handling:** Return `Err(ServiceError.NetworkError(...))` for network failures, `Err(ServiceError.AuthError(...))` for invalid keys, etc. Never throw exceptions for expected failures.

**Language support options:**
- `SupportedLanguages.All` — supports any language
- `SupportedLanguages.Specific(setOf(LanguageCode.ENGLISH, LanguageCode.ARABIC, ...))` — fixed list
- `SupportedLanguages.Dynamic` — fetch at runtime by overriding `fetchSupportedLanguages()`

---

## Step 4 — Create plugin.json

Place this file at `src/main/resources/plugin.json`:

```json
{
  "id":            "com.example.my-plugin",
  "name":          "My Plugin",
  "version":       "1.0.0",
  "author":        "Your Name",
  "description":   "Translates text using the Example API.",
  "minApiVersion": "1.0.0",
  "icon":          "assets/icon.svg"
}
```

The `id` must match exactly what you declared in the `Plugin` class. The `minApiVersion` should match the API version you compiled against (currently `1.0.0`).

---

## Step 5 — Register via ServiceLoader

Create `src/main/resources/META-INF/services/com.github.ahatem.qtranslate.api.plugin.Plugin` containing the fully-qualified class name of your Plugin class:

```
com.example.myplugin.MyPlugin
```

This is how QTranslate discovers your plugin at runtime without any reflection tricks.

---

## Step 6 — Build and install

```bash
./gradlew jar
```

The JAR will be in `build/libs/`. Install it in QTranslate:

1. **Settings → Plugins → Install Plugin…**
2. Select your JAR
3. Enable the plugin
4. Configure it (Settings → Plugins → select your plugin → Configure…)
5. Assign it in **Settings → Services & Presets**

---

## Tips

- **Use `PluginContext`** — your `onInitialize` receives a `PluginContext` with a logger, key-value storage, and a coroutine scope. Use `context.storeValue` / `context.getValue` for persisting settings instead of files.
- **Test without reinstalling** — run QTranslate with `-DappData=path/to/test/data` and copy your JAR to the `plugins/` subfolder directly.
- **Icon format** — SVG is strongly preferred. Keep it simple (single-colour works best with FlatLaf's colour filter). 32×32 recommended.
- **Keep it decoupled** — never import anything from `:core` or `:ui-swing`. Your plugin should only depend on `:api`.

---

## Want to share your plugin?

See [Submitting a Plugin to the Community List](https://github.com/ahatem/qtranslate/issues/new?template=plugin_submission.md).