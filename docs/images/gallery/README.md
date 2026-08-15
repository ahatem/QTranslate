# Screenshot gallery

Every image here is a capture of the running application — real plugins, real translation
requests, real dictionary lookups. Nothing is staged or mocked up, and nothing is an upscale:
they are rendered at twice the size natively, so they stay sharp on high-density displays.

The six images one level up (`docs/images/screenshot-*.png`) are the subset the README uses.
This folder is the full set, and is what the website gallery draws from.

## The set

| File | Shows |
| --- | --- |
| `main-dark`, `main-light` | The main window in both themes, classic layout |
| `layout-side-by-side-dark`, `layout-side-by-side-light` | Source and translation in parallel columns |
| `layout-compact-dark`, `layout-compact-light` | Compact layout, where the panes become tabs |
| `hero-dark`, `hero-light` | Everything at once: input, translation, backward translation and the docked dictionary |
| `dictionary-docked-dark`, `dictionary-docked-light` | The dictionary panel beside a translation |
| `dictionary-quick-dark` | The floating dictionary popup, which is what <kbd>Ctrl+D</kbd> opens over other apps |
| `quick-translate-dark`, `quick-translate-light` | The <kbd>Ctrl+Q</kbd> popup — select text anywhere, read it translated |
| `rtl-main`, `rtl-compact`, `rtl-dictionary` | The Arabic interface, mirrored right to left |
| `history` | The translation history |
| `document-translation` | Translating a document while preserving its structure |
| `settings-*-dark`, `settings-*-light` | All eight settings pages, in both themes |

## Regenerating

These are produced by a capture harness that drives the real application window and paints it to
a PNG. The harness is kept out of the repository — it exists to maintain this folder, not to ship
with the app — so regenerating them is a maintainer task rather than something a contributor
needs to run.

If a screenshot is out of date, open an issue rather than replacing it by hand: a hand-taken
screenshot will not match the rest of the set on scale, window size or content.
