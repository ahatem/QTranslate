# Windows Build and Run Guide

Step-by-step guide to building, packaging as `.exe`, locating, and running the latest QTranslate Windows artifacts.

---

## Why is the Default Build Output `.jar` Instead of `.exe`?

QTranslate is written in Kotlin for the Java Virtual Machine (JVM).
- **Default Gradle tasks (`build`, `assemblePortable`):** Produce `.jar` and `.zip` packages because they are cross-platform JVM artifacts.
- **Standalone `.exe` (`QTranslate.exe`):** Requires an additional packaging step using OpenJDK's `jpackage` and `jlink` tools. This bundles a customized, lightweight Java Runtime Environment (JRE) alongside a native Windows `.exe` launcher, so end-users don't need Java installed on their machine.

---

## Prerequisites

- **Operating System:** Windows 10/11 (x64)
- **Runtime:** Java 17 or higher (e.g., Microsoft OpenJDK or Eclipse Temurin)
- **PowerShell:** Run commands using Windows PowerShell with script prefix `.\`

> **Note:** The project auto-provisions required Java toolchains (JDK 11 and JDK 21) via the Foojay resolver configured in `settings.gradle.kts` and `buildSrc/settings.gradle.kts`.

---

## Step 1: Build the Application

Open PowerShell in the project root (`QTranslate`) and run the desired build task:

### Option A: Assemble Full Portable Distribution (Recommended)
Compiles the application and bundles all plugins, themes, languages, and icons:
```powershell
.\gradlew.bat assemblePortable
```

### Option B: Fast Compilation & Verification (All Modules & Tests)
```powershell
.\gradlew.bat build
```

### Option C: Clean Build
```powershell
.\gradlew.bat clean assemblePortable
```

---

## Step 2: Locate the Output Artifacts

All built distributions are placed under the following directories:

### 1. Standalone Windows Application with `.exe` (Embedded Java Runtime)
- **Folder Path:**
  ```
  build\windows-package\app-image\QTranslate\
  ```
- **Executable File:**
  ```
  build\windows-package\app-image\QTranslate\QTranslate.exe
  ```
- **Windows ZIP Archive:**
  ```
  build\windows-package\QTranslate-1.5.0-windows-x64.zip
  ```
*(Does not require Java to be installed on the target machine; it includes its own embedded lightweight runtime).*

### 2. Full Portable Bundle (.jar + plugins)
- **Folder Path:**
  ```
  build\release\QTranslate-1.5.0\QTranslate\
  ```
- **Archive File:**
  ```
  build\release\QTranslate-1.5.0.zip
  ```
- **Contents:**
  - `QTranslate.jar` — Main executable application JAR (all dependencies bundled)
  - `plugins\` — All core service plugins (Google, DeepL, Bing, Speech, System, AI, etc.)
  - `languages\` — Interface localization files (e.g., `ar-SA.toml`, `en-GB.toml`)
  - `themes\` — Built-in visual themes
  - `icons\` — Application icon sets

### 3. Standalone Application JAR & Distribution Archives
- **Shadow Executable JAR (App only):**
  ```
  app\build\libs\QTranslate.jar
  ```
- **ZIP Distribution Archive:**
  ```
  app\build\distributions\app-shadow-dev.zip
  ```

---

## Step 3: Run the Application

You can launch QTranslate using any of the following methods:

### Method 1: Launch Native `QTranslate.exe` (No Java Required)
Double-click:
```
build\windows-package\app-image\QTranslate\QTranslate.exe
```
Or launch from PowerShell:
```powershell
& "build\windows-package\app-image\QTranslate\QTranslate.exe"
```

### Method 2: Launch Directly from PowerShell with Plugins (Development Mode)
```powershell
.\gradlew.bat runWithPlugins
```
*Loads all plugins in an isolated staging environment under `app/build/manual-qa/` without affecting your user settings.*

### Method 3: Launch the Assembled Portable Build
```powershell
java -jar "build\release\QTranslate-1.5.0\QTranslate\QTranslate.jar"
```
*To specify a custom user data directory (settings, history, plugins):*
```powershell
java -DappData="C:\Users\%USERNAME%\QTranslateData" -jar "build\release\QTranslate-1.5.0\QTranslate\QTranslate.jar"
```

---

## Step 4: (Optional) Package as Standalone Windows Executable (`.exe`)

To generate a standalone Windows app bundle with embedded runtime (`QTranslate.exe`):

1. Ensure `jpackage` and `jlink` are available in your `PATH` (included with JDK 17+).
2. Generate the portable release first:
   ```powershell
   .\gradlew.bat assembleReleaseVariants "-PreleaseVersion=1.5.0"
   ```
3. Run the Windows packaging script:
   ```powershell
   & .\.github\scripts\package-windows.ps1 -Version "1.5.0" -PortableArchive "build\release\QTranslate-1.5.0.zip" -OutputDirectory "build\windows-package"
   ```
4. Output executable will be located at:
   ```
   build\windows-package\app-image\QTranslate\QTranslate.exe
   ```
