# Adding a Theme

QTranslate supports custom visual themes using the IntelliJ theme format (`.theme.json`). You can install community themes, create your own, or contribute a bundled theme to the project.

---

## How themes work

QTranslate has two layers of themes:

| Layer | Where | How it ships |
|-------|-------|-------------|
| **Built-in themes** | Embedded inside the JAR | Always available — no files needed |
| **External themes** | `themes/` folder next to the JAR | Scanned at startup; add/remove without rebuilding |

The `themes/` folder ships in the release zip with an example theme already in it. Drop any `.theme.json` file there and it appears in **Settings → Appearance → Theme** on next startup.

---

## Installing a community theme

**1. Find a theme**

IntelliJ themes use a standard JSON format. Good sources:

- [JetBrains Marketplace](https://plugins.jetbrains.com/search?tags=Theme) — search for "theme", download the plugin, unzip it, and extract the `.theme.json` file
- [FlatLaf IntelliJ Themes Pack](https://github.com/JFormDesigner/FlatLaf/tree/main/flatlaf-intellij-themes/src/main/resources/com/formdev/flatlaf/intellijthemes) — the themes already bundled in QTranslate (for reference)
- GitHub — search for `intellij theme.json` or `*.theme.json`

The file must be a valid IntelliJ theme JSON that FlatLaf can load. If `IntelliJTheme.createLaf()` throws an exception, the theme is silently skipped and an error is logged.

**2. Find your QTranslate data folder**

Run QTranslate from a terminal (`java -jar QTranslate.jar`) and look for this line in the startup output:
```
[INFO] [Main] App data directory: C:\Users\you\QTranslate
```

**3. Copy the theme file**

Drop the `.theme.json` file into the `themes/` subfolder:
```
QTranslate/
  ├── QTranslate.jar
  ├── themes/
  │     └── my-cool-theme.theme.json   ← here
  ├── plugins/
  └── languages/
```

The filename must contain `"theme"` (case-insensitive) and have the `.json` extension — for example, `mytheme.theme.json` or `MyTheme.theme.json`. Files that don't match this filter are ignored.

**4. Select the theme**

Go to **Settings → Appearance → Theme** and select your new theme from the list. The theme name shown comes from the `"name"` field inside the JSON, not the filename.

> **No restart required** — the theme list reloads the next time you open Settings.

---

## Theme format

QTranslate uses IntelliJ's `.theme.json` format, loaded by FlatLaf. A minimal valid theme looks like:

```json
{
  "name": "My Cool Theme",
  "dark": true,
  "author": "your-name",
  "editorScheme": "/theme.xml",
  "ui": {
    "Button.background": "#2D2D2D",
    "Button.foreground": "#FFFFFF",
    "Panel.background": "#1E1E1E"
  }
}
```

Key fields:

| Field | Purpose |
|-------|---------|
| `"name"` | Display name shown in Settings. Required. |
| `"dark"` | `true` for dark themes, `false` for light. Controls which section of the theme list it appears in. Required. |
| `"ui"` | Map of FlatLaf UI keys to colour values. At minimum, set `Panel.background` and a few component colours. |
| `"icons"` | Optional: icon colour overrides for icon tinting. |
| `"editorScheme"` | Optional: path to an IntelliJ colour scheme XML inside your JAR (not used for external themes). |

You can explore and preview all available UI keys interactively using the [FlatLaf Theme Editor](https://www.formdev.com/flatlaf/theme-editor/).

---

## Creating a theme from scratch

The fastest approach is to copy an existing bundled theme and modify it:

1. Open `QTranslate.jar` with any ZIP tool
2. Navigate to `themes/` and copy a `.theme.json` that is close to what you want (e.g. `ReSharperDark.theme.json` for a dark base)
3. Rename the copy — `my-theme.theme.json`
4. Edit `"name"` to your theme's display name
5. Adjust colours in the `"ui"` section
6. Drop it in the `themes/` folder next to the JAR and test it

> **Tip:** QTranslate adapts its icons to dark/light themes automatically via FlatLaf's icon filter. If you are creating a dark theme, make sure `"dark": true` is set — otherwise icons may render with incorrect tinting.

---

## Contributing a theme to the release

If your theme looks great and you'd like it shipped in every QTranslate release zip:

1. Add the `.theme.json` file to the `themes/` folder at the project root
2. Open a pull request with a screenshot showing the theme applied to the main window

That's it. The `themes/` folder is copied into the release zip as-is, so your theme lands in every user's installation automatically. Users see it immediately in **Settings → Appearance → Theme** without any extra steps.

> The JAR also contains 17 themes embedded inside it — but those require code changes in `ThemeManager.kt` to register. Contributing to the `themes/` folder is the no-code path and is preferred for community themes.

---

## Troubleshooting

**Theme doesn't appear in Settings**

- Make sure the filename contains `"theme"` and ends with `.json` (e.g. `my.theme.json`, not `my-style.json`)
- Check the log file for `"Failed to load external theme"` — the JSON may be malformed or use keys that the FlatLaf version bundled with QTranslate doesn't support
- Restart QTranslate if you added the file while it was running (the theme list reloads on next Settings open, but a fresh start is safest)

**Theme loads but colours look wrong**

The `"dark"` field controls icon tinting and some automatic colour adjustments. If a dark theme shows light icons (or vice versa), double-check that `"dark"` is set correctly in the JSON.

**The theme is listed but applying it crashes**

Run QTranslate from a terminal (`java -jar QTranslate.jar`) to see the full log output. If `FlatLaf.setup()` throws, the theme JSON is likely using an unsupported IntelliJ-specific extension. Check the [FlatLaf Theme Editor](https://www.formdev.com/flatlaf/theme-editor/) for which properties are supported.
