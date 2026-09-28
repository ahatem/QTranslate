# System Services

The bundled System Services plugin provides offline OCR, text-to-speech, and spell checking using
each platform's own capabilities — no account, no API key, and no network access for any of the
three.

It has no settings to configure. Enable it under **Settings → Plugins → System Services → Enable**,
then assign whichever of its services you want under **Settings → Services & Presets**.

## What each capability needs

| Capability | Windows | macOS | Linux |
|---|---|---|---|
| OCR | Built in (Windows OCR API) | Built in (a bundled Vision helper) | Needs `tesseract` on `PATH` |
| Text-to-speech | Built in | Built in (`say`) | Needs `espeak` or `espeak-ng` on `PATH` |
| Spell checking | Built in | Built in (a bundled helper) | Needs `enchant-2` (preferred) or `hunspell` on `PATH` |

Windows and macOS are self-contained — nothing to install. On Linux, each capability discovers
whatever tool you already have; install the one you're missing with your distribution's package
manager, for example:

```bash
# Debian/Ubuntu
sudo apt install tesseract-ocr espeak-ng enchant-2

# Fedora
sudo dnf install tesseract espeak-ng enchant2
```

## How discovery works

System Services doesn't fail as a whole if one capability is unavailable. Each of OCR, TTS, and
spell checking is discovered independently when the plugin is enabled: if a required host tool is
missing, that one capability simply doesn't appear as a service — the other two still work if their
own requirements are met. Nothing is silently degraded; a missing tool is a missing service, not a
broken one.

## Troubleshooting

**A System Services capability isn't showing up under Services & Presets**
The plugin is enabled but that particular capability wasn't found at enable time — most often a
missing host tool on Linux (see the table above). Install the tool, then disable and re-enable the
plugin so it re-discovers what's available.

**OCR/TTS/spell checking worked before, but stopped after a system update**
The underlying platform tool changed or was removed. Re-check it's still on `PATH` (`which tesseract`,
`which espeak-ng`, `which enchant-2`), then re-enable the plugin.

**Spell-check suggestions look wrong for my language**
The dictionary for that language may not be installed alongside `enchant`/`hunspell`. On Linux,
install the language-specific dictionary package for your spell-check provider (e.g.
`hunspell-<locale>` or an `enchant` provider's language pack).
