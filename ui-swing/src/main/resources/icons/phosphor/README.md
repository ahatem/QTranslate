# phosphor

Drop the SVGs for this set here, named for the vocabulary in
`ui-swing/.../shared/icon/IconSet.kt` — `edit.svg`, `delete.svg`, `ocr.svg` and so on.
The full list of names is the `Icons` object in that file.

The set does not have to be complete. Anything missing falls back to Lucide, so the set is usable
from its first icon. It only appears in Settings once it holds at least one of
`settings`, `close`, `search` or `edit`, so this folder is invisible until it has something in it.

Two names are easy to get wrong when picking glyphs:

- `language` is the interface-language setting (a globe). `translate` is the translator service.
- `check` is used both for the spell-checker service and for the done tick in the translation editor.
