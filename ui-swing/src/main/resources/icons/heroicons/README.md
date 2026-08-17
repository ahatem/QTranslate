# heroicons

29 of the 32 semantic names, taken from the set's own published glyphs.

Missing: pin, unpin and keyboard, which Heroicons does not publish. Those fall back to Lucide, which is the designed behaviour — a wrong glyph would
be worse than a borrowed one.

The names are QTranslate's vocabulary, not the set's, so `edit.svg` is whichever glyph this set
uses for editing. The full list is the `Icons` object in
`ui-swing/.../shared/icon/IconSet.kt`.

Three are easy to mismatch when replacing a glyph:

- `language` is the interface-language setting (a globe). `translate` is the translator service.
- `check` serves both the spell-checker service and the done tick in the translation editor.
- `service` is the Services & Presets section, not a generic gear.
