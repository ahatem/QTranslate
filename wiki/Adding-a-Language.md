# Adding a UI Language

QTranslate's interface can be translated into any language using a plain TOML file. No programming knowledge required — if you can edit a text file, you can add a language.

---

## Bundled languages

These languages ship with QTranslate and are always available:

| Language | File | RTL |
|----------|------|-----|
| English (US) | `en` *(default)* | No |
| English (UK) | `en-GB` | No |
| Arabic (Saudi Arabia) | `ar-SA` | **Yes** |
| Chinese (Simplified) | `zh-CN` | No |
| French | `fr-FR` | No |
| German | `de-DE` | No |
| Japanese | `ja-JP` | No |
| Portuguese (Brazil) | `pt-BR` | No |
| Russian | `ru-RU` | No |
| Spanish (Spain) | `es-ES` | No |
| Turkish | `tr-TR` | No |

---

## Quick start

**1. Find your QTranslate data folder**

The path is printed in the logs at startup:
```
[INFO] [Main] App data directory: C:\Users\you\QTranslate
```

**2. Copy the English source file**

Open the `languages/` subfolder. Copy `en.toml` and rename it to your language code:

| Language | Filename |
|----------|----------|
| Arabic | `ar.toml` |
| French | `fr.toml` |
| German | `de.toml` |
| Japanese | `ja.toml` |
| Chinese (Simplified) | `zh.toml` |
| Chinese (Traditional) | `zh-TW.toml` |

Use [IETF BCP 47](https://en.wikipedia.org/wiki/IETF_language_tag) codes. When in doubt, use the two-letter ISO 639-1 code (`ar`, `fr`, `de`...).

**3. Translate the values**

Open your new file in any text editor and translate the values. Keys stay in English — only values change.

**4. Select your language**

Go to **Settings → Appearance → Interface Language** and select your language. Changes apply immediately.

---

## File format

A translation file has two parts: a `[meta]` section that describes the translation itself, and content sections that contain the actual UI strings.

### The meta section

This must be at the top of every file:

```toml
[meta]
name         = "Arabic"          # language name in English
native_name  = "العربية"         # language name in its own script
locale       = "ar"              # IETF BCP 47 code
version      = "1.0.0"           # your translation version
author       = "Your Name"       # your name or GitHub username
last_updated = "2025-01-01"      # date you last updated it
rtl          = true              # true for right-to-left languages, false otherwise
```

`rtl = true` tells QTranslate to mirror the entire layout — all panels, buttons, and text alignment flip automatically. Set this for Arabic, Hebrew, Farsi, Urdu, and any other right-to-left language.

### Content sections

The rest of the file is organised into sections, each covering a different part of the UI. Here are the most important ones to translate first:

```toml
# ── Shared strings used across the whole app ──────────────────────────────
[common]
ok     = "حسناً"
cancel = "إلغاء"
save   = "حفظ"
apply  = "تطبيق"
close  = "إغلاق"
yes    = "نعم"
no     = "لا"
delete = "حذف"
browse = "تصفح..."

# ── Main translation window ────────────────────────────────────────────────
[main_window]
status_format = "استخدام %s: %s → %s"   # see placeholders below
no_translator = "لا يوجد مترجم"

[main_window_language_bar]
translate_button       = "ترجم"
clear_tooltip          = "مسح النص"
swap_languages_tooltip = "تبديل اتجاه الترجمة"

# ── Settings dialog ────────────────────────────────────────────────────────
[settings_dialog]
title           = "الإعدادات"
unsaved_changes = "● تغييرات غير محفوظة"

[settings_dialog_sidebar]
general     = "عام"
appearance  = "المظهر"
services    = "الخدمات والإعدادات المسبقة"
plugins     = "الإضافات"
hotkeys     = "لوحة المفاتيح والاختصارات"
translation = "الترجمة"
```

The full list of sections is in `en.toml` — every section and key is documented there with comments. You don't have to translate everything at once. Any key that is missing falls back to the English value automatically — the app never shows a blank label.

### Placeholders

Some strings contain `%s` placeholders that get replaced with dynamic values at runtime. Keep them in your translation — their count and order must stay the same.

```toml
# English source:
status_format = "Using %s: %s → %s"
# %1 = service name, %2 = source language, %3 = target language

# Correct Arabic translation — same three %s, same order:
status_format = "استخدام %s: %s → %s"

# Wrong — missing one placeholder:
status_format = "استخدام %s → %s"    # will crash at runtime
```

Comments above each key in `en.toml` describe what each placeholder represents.

### Reference syntax

A value starting with `@` reuses another key's value. This avoids repeating the same translation:

```toml
[common]
ok = "حسناً"

[some_dialog]
confirm_button = "@common.ok"    # displays "حسناً" — no need to write it again
```

The format is `@section.key`. References are resolved at load time. You can use them freely, or just write the value directly — both work.

---

## Common mistakes

**Translating a key instead of a value**
```toml
حسناً = "OK"      # ❌ key is in Arabic — won't be recognised
ok = "حسناً"      # ✅ key is in English, value is translated
```

**Breaking a placeholder**
```toml
version = "الإصدار"      # ❌ removed the %s — version number won't appear
version = "الإصدار %s"   # ✅
```

**Removing quotes**
```toml
ok = حسناً      # ❌ TOML requires quotes around string values
ok = "حسناً"    # ✅
```

**Wrong filename**

The filename must be a valid BCP 47 code. `arabic.toml` won't be recognised — use `ar.toml`. `chinese-traditional.toml` won't work — use `zh-TW.toml`.

---

## Tips

- **Start with `[common]`** — these strings appear everywhere and have the most visible impact per line translated
- **RTL and fonts** — if Arabic, Hebrew, or other RTL text looks wrong, make sure your system has the fonts installed. QTranslate uses your system's fallback font for characters the UI font can't render
- **Reload without restarting** — switch to a different language in Settings and back to reload your file. No restart needed
- **When in doubt, leave it in English** — a fallback is always better than a wrong translation. Mistranslated button labels are confusing; English fallbacks are just slightly incomplete

---

## Submitting your translation

Two options:

**Option 1 — Pull request (preferred)**
1. Fork the repository on GitHub
2. Copy your `.toml` file into `app/src/main/resources/languages/`
3. Open a PR against the `develop` branch with the title: `feat(i18n): add <language name> translation`

**Option 2 — Just attach it**

[Open an issue](https://github.com/ahatem/qtranslate/issues/new) and attach the `.toml` file — we'll add it for you. No Git required.

---

## Keeping a translation up to date

When new UI strings are added to the app, new keys appear in `en.toml`. Missing keys fall back to English automatically so nothing breaks — but some labels will appear in English until you translate them.

To stay current:
- **Watch `en.toml` on GitHub** — you'll get a notification when it changes
- **Check the CHANGELOG on each release** — new keys are listed under "Added"
- **Diff your file against `en.toml`** — any key present in `en.toml` but missing in yours needs translating