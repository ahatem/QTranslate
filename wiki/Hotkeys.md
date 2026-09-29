# Hotkeys

QTranslate is built around being usable entirely from the keyboard. Every action has a hotkey, and
every hotkey can be rebound from **Settings → Keyboard & Hotkeys**.

## Global vs. app-local

Each hotkey has a scope:

- **Global** — fires anywhere, in any application, whether or not QTranslate is focused. Used for
  actions you trigger on text selected in some other app: Quick Translate, Listen, OCR, and similar.
- **App-local** — fires only while a QTranslate window has focus. Used for in-app actions like
  clearing the input, swapping languages, or opening settings, so the combination doesn't get taken
  away from every other application on your system.

**Show main window** is a special case: instead of a configurable key combination, it fires on a
double press of Ctrl (press Ctrl twice in quick succession). This can be turned off in Keyboard &
Hotkeys if you don't want it.

## Presets

Rather than rebind everything by hand, pick a preset from the dropdown at the top of Keyboard &
Hotkeys:

- **Legacy QTranslate** — the original QTranslate key scheme (`Ctrl+Q` for Quick Translate, `Ctrl+E`
  to listen, `Ctrl+I` for OCR, and so on).
- **Modern** — an alternate scheme that moves most popup-opening actions onto `Ctrl+Shift+<letter>`
  combinations.
- **Custom** — shown automatically the moment your bindings no longer match either preset exactly.
  There's nothing to select for Custom; it just reflects what you've changed.

Switching presets replaces every binding at once. If you only want to change one action, edit it
directly instead — QTranslate will show **Custom** afterward, and that's expected.

## Changing a binding

1. Open **Settings → Keyboard & Hotkeys**
2. Click the binding you want to change
3. Press the new key combination
4. Click **Apply**

## Troubleshooting

**A global hotkey doesn't fire**
Another application (or the OS itself) may already own that key combination. Try a different
combination for the action, or close the application that might be intercepting it.

**A hotkey works sometimes but not others**
Global hotkeys that capture selected text (Quick Translate, Listen, OCR, Replace, Dictionary,
Images) need something selected first. If nothing is selected when the hotkey fires, there's
nothing to act on.

**Double-Ctrl doesn't show the main window**
Confirm it's still enabled in Keyboard & Hotkeys — it has its own toggle, separate from the rest of
the bindings. It also only counts two *physical* Ctrl presses in quick succession; a remapped or
software-injected Ctrl key may not register the same way.
