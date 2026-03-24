# Adding a UI Language

QTranslate's interface can be translated into any language. Translations are plain TOML files — no programming knowledge required.

---

## Quick start

1. Locate your QTranslate data folder. The path is printed at startup:
   ```
   [INFO] [Main] App data directory: C:\Users\you\QTranslate
   ```
2. Open the `languages/` subfolder inside it.
3. Find `en.toml` — this is the English source file with all available keys.
4. Copy it and rename the copy to your language code, for example:
   - Arabic → `ar.toml`
   - French → `fr.toml`
   - Japanese → `ja.toml`
   - Use [IETF BCP 47](https://en.wikipedia.org/wiki/IETF_language_tag) codes.
5. Open your new file in any text editor and translate the values.
6. In QTranslate, go to **Settings → Appearance** and select your language from the dropdown.

---

## File format

```toml
[meta]
name        = "Arabic"
native_name = "العربية"
locale      = "ar"
version     = "1.0.0"
author      = "Your Name"
rtl         = true        # set to true for right-to-left languages

[ui.main]
translate_button = "ترجم"
clear_button     = "مسح"

[ui.settings]
title = "الإعدادات"
```

**Rules:**
- The **keys** must stay in English exactly as they appear in `en.toml`
- Only translate the **values** (the text after the `=` sign)
- Keep `"` quote marks around values that have them
- The `[meta]` section at the top is about your translation file, not UI text — fill it in accurately
- Set `rtl = true` if your language reads right to left

**Reference syntax:** A value starting with `@` copies another key's value:
```toml
ok_button     = "OK"
confirm_button = "@ok_button"   # reuses "OK" — useful for shared strings
```

---

## Tips

- Translate everything, even short strings like button labels — small strings matter to users
- If you're unsure about a translation, leave the English value in place — it will fall back gracefully
- Test by selecting your language in Settings before submitting

---

## Submitting your translation

Sharing your translation helps other users who speak your language. Here's how:

1. Fork the repository on GitHub
2. Copy your `.toml` file into `app/src/main/resources/languages/` in your fork
3. Open a Pull Request against the `develop` branch
4. Title it `feat(i18n): add <language name> translation`

If you're not comfortable with Git, you can also [open an issue](https://github.com/ahatem/qtranslate/issues/new) and attach the file — we'll add it for you.

---

## Keeping a translation up to date

When new UI strings are added to the app, `en.toml` gets new keys. Translations with missing keys automatically fall back to English for those strings. If you maintain a translation and want to be notified when `en.toml` changes, watch the file on GitHub or check the diff on each release.