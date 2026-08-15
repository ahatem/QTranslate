# AI Services setup

The bundled AI Services plugin works with OpenRouter by default and can also use any OpenAI-compatible endpoint.

## OpenRouter quick start

1. Create an account at [OpenRouter](https://openrouter.ai/) and create an API key.
2. In QTranslate, open **Settings > Plugins > AI Services > Configure**.
3. Keep the Base URL as `https://openrouter.ai/api/v1`.
4. Paste the API key into **API Key**.
5. Keep the Model as `openrouter/free` for automatic free-model routing, or copy an exact current model slug from the [OpenRouter model list](https://openrouter.ai/models).
6. Save the plugin settings and select an AI service under **Settings > Services & Presets**.

No additional OpenRouter site configuration is required. Free accounts are rate-limited, and model availability can change over time.

## Running it locally, with nothing leaving your machine

The plugin talks to any OpenAI-compatible endpoint, and both Ollama and LM Studio serve one. Point
the Base URL at it and no text is sent anywhere off your machine: no account, no API key, no
per-word cost, and no rate limit.

This is the setup to use for anything you cannot send to a cloud service — work under an NDA,
proprietary code, client correspondence, medical or legal material.

### Ollama

1. Install [Ollama](https://ollama.com/) and pull a model. `qwen2.5` handles many languages well and
   runs on modest hardware:

   ```
   ollama pull qwen2.5
   ```

2. Ollama serves its OpenAI-compatible API on port 11434 while it is running. Check it:

   ```
   curl http://localhost:11434/v1/models
   ```

3. In QTranslate, open **Settings > Plugins > AI Services > Configure** and set:

   | Setting | Value |
   | --- | --- |
   | Base URL | `http://localhost:11434/v1` |
   | API Key | *leave blank* |
   | Model | `qwen2.5` |

4. Press **Test connection**. It should report the service responding.
5. Select the AI translator under **Settings > Services & Presets**.

### LM Studio

Same idea. Start its local server, then use `http://127.0.0.1:1234/v1` as the Base URL with the model
name LM Studio shows for the model you loaded. The API key stays blank.

### Notes

- **The API key field is genuinely optional here.** A local endpoint needs none, and QTranslate only
  asks for one when the Base URL points somewhere off this machine. An address on your own network
  counts as local too, so a model served from another machine on the same LAN also works without a
  key.
- **Quality varies by model far more than with a hosted service.** A small local model will produce
  weaker translations than a large hosted one. Try a larger model before concluding the setup is
  wrong.
- **The first request after starting Ollama is slow** while the model loads into memory. Later ones
  are much faster.
- **Nothing else in QTranslate changes.** Hotkeys, the popup, the dictionary and document translation
  all work the same; only where the text is sent is different.

## Troubleshooting

- **HTTP 401 or 403:** The API key is missing, invalid, or not allowed to use the selected provider.
- **HTTP 402:** Add credits or use `openrouter/free`.
- **HTTP 404:** The Base URL is incorrect or the model slug was renamed or removed. Restore the default Base URL and choose a current model slug.
- **HTTP 429:** The provider or free tier is rate-limiting requests. Wait and retry, or choose another model.
- **Vision OCR fails:** The selected model must support image input. `openrouter/free` automatically restricts routing according to request capabilities, but a manually selected text-only model cannot perform OCR.
- **"No API key set" with a local endpoint:** The Base URL is not being recognised as local. Use `localhost`, `127.0.0.1`, or a private address such as `192.168.x.x` rather than a hostname that resolves off your network.
- **Connection refused with a local endpoint:** The server is not running, or is on a different port. Confirm with `curl http://localhost:11434/v1/models` before changing anything in QTranslate.

For OpenAI, Gemini, Mistral, or another hosted server, replace the Base URL, API key, and model with the values documented by that provider.
