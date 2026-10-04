# Bundled font notices

The fonts listed below are bundled with QTranslate. They retain their original licenses and are not relicensed under the QTranslate project license.

Fonts named here are used either as a font a user can select, or as a fallback face for translated text whose script the selected fonts cannot draw. The interface typeface is supplied by FlatLaf's Inter and is not listed.

| Bundled font | Purpose | Source | License file |
|---|---|---|---|
| Rubik | Selectable editor and interface font | [Rubik](https://github.com/googlefonts/rubik) | [Rubik-OFL.txt](Rubik-OFL.txt) |
| IBM Plex Sans, IBM Plex Serif | Available to the document translation renderer | [IBM Plex](https://github.com/IBM/plex) | [IBM-Plex-OFL.txt](IBM-Plex-OFL.txt) |
| Noto Naskh Arabic | Selectable Arabic fallback | [Noto Naskh Arabic](https://github.com/notofonts/arabic) | [Noto-OFL.txt](Noto-OFL.txt) |
| Noto Sans Bengali | Fallback for Bengali text | [Noto Sans Bengali](https://github.com/notofonts/bengali) | [Noto-OFL.txt](Noto-OFL.txt) |
| Noto Sans Devanagari | Fallback for Devanagari text, including Hindi and Nepali | [Noto Sans Devanagari](https://github.com/notofonts/devanagari) | [Noto-OFL.txt](Noto-OFL.txt) |
| Noto Sans Thai | Fallback for Thai text | [Noto Sans Thai](https://github.com/notofonts/thai) | [Noto-OFL.txt](Noto-OFL.txt) |

The Noto faces are licensed under the SIL Open Font License, Version 1.1. Their license text is the same for every face, so [Noto-OFL.txt](Noto-OFL.txt) covers all of them.

A fallback face is reached only when the fonts a user has selected cannot draw a grapheme cluster of a translation, so it is the last candidate in the chain rather than a replacement for the configured fonts.
