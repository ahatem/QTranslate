# Hotkeys

QTranslate is built around being usable entirely from the keyboard. Every command has a hotkey, and
every hotkey can be rebound from **Settings → Keyboard & Hotkeys**.

## Global vs. app-local

Each hotkey has a scope:

- **Global** — fires anywhere, in any application, whether or not QTranslate is focused. Used for
  actions you trigger on text selected in some other app, or for starting screen OCR from any
  application.
- **App-local** — fires only while a QTranslate window has focus. Used for in-app actions like
  clearing the input, swapping languages, or opening settings, so the combination doesn't get taken
  away from every other application on your system.
- **Capture** — fires only while the screen-capture selection overlay is open. These actions
  operate the capture toolbar (Translate, Copy Text, Copy Image, Save Image, Re-crop, Cancel).
  They are never registered globally or in the main window. See
  [Screen OCR](OCR.md#capture-workflows).

**Show main window** is a special case: instead of a configurable key combination, it fires on a
double press of Ctrl (press Ctrl twice in quick succession). This can be turned off in Keyboard &
Hotkeys if you don't want it.

## Finding a command

**Settings → Keyboard & Hotkeys** is the full list of commands, one row each, grouped by what they
are for: translation, writing, dictionary, screen OCR, the capture overlay, focus and navigation,
editing, documents, and window controls.

The list is a window onto the commands rather than the whole list at once: it scrolls inside the
page, and the wheel keeps working on the page once the list has reached the end that the pointer
is heading for.

Select a row to read what that command does and, where it matters, what it needs. Three things are
worth knowing before you bind a key:

- **Selection commands act on another application.** Quick Translate, Listen, Replace, Dictionary,
  Images, and the two writing commands work on whatever is selected in the window you were just
  using: QTranslate copies that selection first, then shows, speaks, or replaces it. They are not
  actions on QTranslate's own text panes.
- **Writing commands need a service.** Rewrite needs a rewriter service and Summary needs a
  summarizer service. Without one, the command reports that nothing is configured instead of
  silently doing nothing.
- **These two ship unassigned.** Rewrite and Summary have no shortcut until you give them one, and
  they have no menu entry: opening a QTranslate menu moves focus away from the application whose
  text they would act on, so a menu entry would act on the wrong text. Pick them from the command
  list and record a combination.

The **Show** switch above the list narrows it to **All**, **Unassigned** (commands with no
shortcut — a quick way to see what is still available to bind), or **Conflicts**.

## Presets

Rather than rebind everything by hand, pick a preset from the dropdown at the top of Keyboard &
Hotkeys:

- **Legacy QTranslate** — the original QTranslate key scheme (`Ctrl+Q` for Quick Translate, `Ctrl+E`
  to listen, `Ctrl+I` for OCR, and so on).
- **Modern** — an alternate scheme that moves most popup-opening actions onto `Ctrl+Shift+<letter>`
  combinations.

Applying a preset replaces the **original QTranslate shortcuts only**. Screen OCR workflows and the
two writing commands keep whatever you gave them, because neither is part of a preset — they cover
commands that a preset never shipped a default for.

**Custom** is not a preset you apply. It is a description of the current state: it appears beside
the dropdown when your shortcuts no longer match Legacy or Modern exactly, which happens the moment
you change, clear, or add a single binding. Nothing is cleared by it. To change one action, edit
that row directly; to go back to a shipped scheme, apply Legacy or Modern.

## When two shortcuts clash

Nothing stops you from giving two commands the same combination, and the assignment is saved either
way. QTranslate tells you about it in two places:

- **While recording**, the dialog warns before you confirm, naming the commands that already use
  that combination.
- **In the list**, conflicting rows are marked, and a line under the table names the shared
  combination and every command involved.

Two global commands on one combination is the case to avoid: only one of them can be registered
with the system, so the other silently stops firing.

## Changing a binding

1. Open **Settings → Keyboard & Hotkeys**
2. On the command's own row, click the pencil; or select the row and press Enter, double-click its
   hotkey cell, or use **Edit…** above the list
3. Press the new key combination
4. Click **Apply**

The bin at the end of a row clears that command's shortcut, leaving its scope as it was.

The **Scope** cell toggles a binding between Global and App-local, and says which one it is now.
That cell stays clickable even while global hotkeys are switched off, because turning a global
shortcut into a local one is how you make it editable again without the global switch.

With **global hotkeys turned off**, global rows read their configured combination but cannot be
edited, since changing a shortcut that is not registered would let the two drift apart. Local rows
stay fully editable.

## Troubleshooting

**A global hotkey doesn't fire**
Another application (or the OS itself) may already own that key combination. Try a different
combination for the action, or close the application that might be intercepting it. A combination
refused at registration is reported in the notification list.

**A hotkey works sometimes but not others**
Global hotkeys that capture selected text (Quick Translate, Listen, Replace, Dictionary, Images,
Rewrite, Summary) need something selected first. If nothing is selected when the hotkey fires,
there's nothing to act on. Screen OCR is different: it opens the capture overlay and does not use
the current selection.

**Double-Ctrl doesn't show the main window**
Confirm it's still enabled in Keyboard & Hotkeys — it has its own toggle, separate from the rest of
the bindings. It also only counts two *physical* Ctrl presses in quick succession; a remapped or
software-injected Ctrl key may not register the same way.
