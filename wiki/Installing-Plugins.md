# Installing Plugins

Plugins extend QTranslate with new translation engines, OCR providers, TTS services, and more. They are standard JAR files that you install at runtime — no restart required.

---

## Installing a plugin

1. Download the plugin `.jar` file from its repository or release page
2. In QTranslate, open **Settings → Plugins**
3. Click **Install Plugin…**
4. Select the `.jar` file
5. The plugin appears in the list — enable it with the **Enable** button
6. If the plugin requires configuration (API key, region, etc.), click **Configure…** and fill in the fields
7. Open **Settings → Services & Presets** and assign the new service to your active preset

---

## Configuring a plugin

Most plugins that connect to external APIs require at least an API key.

1. Go to **Settings → Plugins**
2. Select the plugin in the list
3. Click **Configure…**
4. Fill in the required fields (marked with a red `*`)
5. Click **Save**

The configuration is stored securely in your QTranslate data folder. It is never sent anywhere except to the API the plugin connects to.

---

## Enabling and disabling plugins

You can enable or disable any plugin without uninstalling it. A disabled plugin's services become unavailable until you re-enable it.

- **Enable** — click Enable in the plugin detail panel
- **Disable** — click Disable in the plugin detail panel
- Disabled plugins are remembered across restarts

---

## Uninstalling a plugin

1. Go to **Settings → Plugins**
2. Select the plugin
3. Click **Uninstall**
4. Confirm the prompt

This removes the JAR and all stored plugin data (configuration, keys, cache). It cannot be undone.

---

## Plugin verification

When QTranslate detects that a plugin JAR has changed since the last run (different file hash), it pauses the plugin in **Awaiting Verification** state. This protects you from accidentally running a modified or replaced JAR.

You will see two options:

- **Accept Update** — keep existing plugin data and re-enable. Use this when you intentionally updated the plugin.
- **Clean Install** — wipe all plugin data and start fresh. Use this if you're unsure about the change.

---

## Troubleshooting

**Plugin shows as Failed**
Check the error message in the plugin detail panel. Common causes:
- The plugin was built for a different API version — get a newer version of the plugin
- A dependency is missing from the plugin JAR — contact the plugin author

**Plugin installed but services don't appear in the dropdowns**
Go to **Settings → Services & Presets** and check the dropdowns — the service may be there but not assigned to your active preset.

**Plugin requires a dependency JAR**
Some plugins document additional JARs they need. Install those the same way — via **Install Plugin…**.

---

## Safety note

QTranslate plugins run inside the JVM with full access to your system. Install only plugins from sources you trust. See [SECURITY.md](https://github.com/ahatem/qtranslate/blob/main/SECURITY.md) for more information.
