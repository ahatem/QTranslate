# Screen OCR and the OCR Workspace

QTranslate has one OCR pipeline with several entry points. Every workflow captures a screen
region, chooses a provider through the normal service-preset system, and uses the same OCR
extraction and text-preprocessing code. The difference is only what happens after the selection.

## Capture workflows

The normal **Screen OCR** action (`Ctrl+I` by default) follows **Settings → OCR → After selecting a
screen region**:

| Setting | Result |
|---|---|
| **Show the action toolbar** | The historic workflow: draw a region, then choose Translate, Copy Text, Copy Image, Save Image, Re-crop, or Cancel. This is the default for existing installations. |
| **Translate the recognized text** | Recognize and translate immediately, using the configured destination. |
| **Copy recognized text** | Recognize and copy the text. No translation and no translation window. |
| **Copy the selected image** | Put the image on the clipboard immediately. OCR does not run. |
| **Open the OCR Workspace** | Send the image to the OCR Workspace and recognize it there. |

While drawing an immediate workflow, hold **Shift** when releasing the selection to show the
action toolbar for that one capture. The saved workflow is unchanged.

### Explicit workflow shortcuts

Every workflow can also be bound in **Settings → Keyboard & Hotkeys**:

- Screen OCR — Default
- Screen OCR — Show Actions
- Screen OCR — Translate
- Screen OCR — Translate to Quick Translate
- Screen OCR — Translate to Main Window
- Screen OCR — Open Workspace
- Screen OCR — Copy Text
- Screen OCR — Copy Image

Only the historic Screen OCR action has a default key (`Ctrl+I`, or `Ctrl+Shift+O` in the Modern
preset). The specialized workflows ship unassigned so the application does not take more
system-wide key combinations from other programs.

Capture-only shortcuts are a separate scope. They fire only while the selection overlay is open
and are never installed globally or in the main window: Translate, Copy Text, Copy Image, Save
Image, Re-crop, and Cancel. Enter remains the primary action and Escape remains cancel.

## Translation destinations

When an OCR workflow translates, **Settings → OCR → Translate to** chooses:

- **Main Window** — writes the prepared OCR text to the normal main input and translates there.
- **Quick Translate** — uses the same popup, request ownership, cancellation, comparison, history,
  and auto-copy behavior as the Quick Translate hotkey.
- **OCR Workspace** — opens the workspace, keeps the recognized text editable, and translates it
  there.

The destination has no effect on Copy Image.

## OCR Workspace

The workspace is a compact utility window, separate from the main translator. It accepts:

- a captured screen region;
- an image file;
- an image pasted from the clipboard;
- an OCR result forwarded by a capture workflow.

On the image side it offers zoom, fit, actual size, copy/save image, and an optional OCR-box
overlay. On the text side it offers editable recognized text, provider and recognition-language
selection, re-run, copy, TTS, translation, copy/TTS for the translation, source/target language
controls, and explicit sending to the main window or Quick Translate.

Send to Quick Translate always hands over the recognized or edited **source** text, even when the
workspace already shows a translation. That keeps the canonical Quick request on the same
source→target pair and avoids translating an English result back through the original pair.

The recognized text keeps the language provenance of the OCR run that produced it (the provider's
detected language, or the explicit recognition language that was requested). Changing the
recognition-language dropdown prepares the next run and does not change what language TTS uses for
text that is already on screen.

Re-running OCR is always an explicit action. Changing the OCR provider or language does not
silently overwrite text the user has corrected. A new recognition run does replace it, because
that is the action the user asked for.

Translation from the workspace goes through the canonical main translation workflow. The
workspace owns the OCR source, provider, image, and edited text; it does not create a second
translation engine or history.

## Text preprocessing

**Settings → OCR → Remove line breaks from recognized OCR text** applies the same line-break policy
used by normal input and Quick Translate. It is off by default, so paragraph structure is
preserved. Fast copy and translate workflows use the same preprocessing function as the
workspace.

## Structured OCR geometry

Providers may implement the optional `StructuredOCR` interface in `:api`. It returns the same text
plus line/word/block rectangles in original-image pixels. This is a separate capability rather
than new fields on `OCRResponse`, so existing text-only plugins keep working without recompiling
and no provider has to invent coordinates.

When geometry is available the workspace can draw OCR boxes and connect a clicked image region to
its text. Providers that only return text continue to work; the overlay control stays disabled.
The bundled System OCR provider returns line geometry on Windows when the engine supplies word
bounding rectangles.

## Privacy and ownership

OCR requests are owned by the store or workspace that started them. A newer request cancels and
supersedes an older one, and a late result from a superseded request cannot update state. Closing
the workspace cancels its owned work and stops its callbacks. Translation requests continue to be
owned by the canonical translation system, not by the OCR window.

Logs contain provider names, image dimensions, language tags, character counts, and error types.
They do not contain image data or recognized text.
