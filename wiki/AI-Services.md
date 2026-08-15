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

## Troubleshooting

- **HTTP 401 or 403:** The API key is missing, invalid, or not allowed to use the selected provider.
- **HTTP 402:** Add credits or use `openrouter/free`.
- **HTTP 404:** The Base URL is incorrect or the model slug was renamed or removed. Restore the default Base URL and choose a current model slug.
- **HTTP 429:** The provider or free tier is rate-limiting requests. Wait and retry, or choose another model.
- **Vision OCR fails:** The selected model must support image input. `openrouter/free` automatically restricts routing according to request capabilities, but a manually selected text-only model cannot perform OCR.

For OpenAI, Gemini, Mistral, Ollama, LM Studio, or another compatible server, replace the Base URL, API key, and model with the values documented by that provider.
