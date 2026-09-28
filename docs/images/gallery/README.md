# Screenshot gallery

These are selected captures of the running application with bundled plugins and chosen sample
inputs. Translation, dictionary and image results come from the actual providers. The harness
renders at 200% through the app's own UI scale for sharp images on high-density displays.

The capture harness produces additional candidates for visual review. This folder contains the
public set: each image should demonstrate a different part of the product. The README embeds a
smaller tour; its main images are copied to `docs/images/`.

## The set

| File | Shows |
| --- | --- |
| `hero-dark` | Translation, backward translation and a docked dictionary |
| `quick-translate-dark` | The <kbd>Ctrl+Q</kbd> popup for selected text |
| `classic-selector-dark`, `classic-selector-narrow-dark` | Compact one-click services at wide and narrow widths, with the active provider and a single More menu |
| `layout-comparison-dark` | Google, Bing, DeepL and Yandex results in one configured Comparison set |
| `dictionary-quick-dark` | The floating dictionary popup for selected words |
| `dock-images-dark`, `dock-image-viewer-dark` | Image results and the opened viewer with source credit |
| `document-translation` | A document selected for translation |
| `settings-services-dark`, `settings-plugins-dark` | Service presets, System Services and runtime plugin management |
| `rtl-main` | The Arabic interface mirrored right to left |

## Regenerating

The capture harness drives the real application window and paints it to PNG. It lives under
`app/src/main/kotlin/com/github/ahatem/qtranslate/app/screenshots/` and runs with:

```
gradlew :app:captureScreenshots
```

Review the generated candidates in `build/screenshots/`, then copy only the selected captures here.
Use `QTRANSLATE_SCREENSHOT_SCENES=presentation` to regenerate the visual audit scenes, including
the Classic selector at two widths and Settings at 100%. The regular run includes the full
candidate set and Settings at 200%.
Use `QTRANSLATE_SCREENSHOT_SCENES=selector` to capture only the Classic selector density audit
at 100% and 200%, plus RTL.
