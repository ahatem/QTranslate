# Conversation Sessions

Conversation Sessions records a voice call — a Discord call, for example — shows a live transcript,
and translates any word you point at. Open it from the main menu or the tray: **Conversation
Sessions...**

- Your microphone and the call's audio are recorded as **two separate streams**. Each line in the
  transcript says where it came from: **Me** (your microphone) or **Call** (everything the call
  played).
- **Hover** a word to see its translation, and what the whole sentence means.
- **Select** a phrase to translate the phrase.
- Click **Meanings** in the card for dictionary entries, or **Play** to hear that word again.
- Click a speaker label (`CALL · 1:05`) to replay that line.
- Sessions are saved automatically. Search them, rename, export the transcript as text, or delete
  them from the list on the left.

## Setup

### 1. A speech recognition provider

Transcription uses the bundled **Speech Recognition** plugin. Open it from the conversation window's
**Speech settings...** button, or from **Settings > Plugins > Speech Recognition > Configure**, and
pick a provider under **Accurate text**:

| Provider | What you enter | Notes |
|---|---|---|
| **None (live text only)** | Nothing | Only with live text (below): the live words are the transcript, and nothing leaves your network. |
| **Groq** | API key from [console.groq.com](https://console.groq.com/) | Fast, inexpensive, has a free tier. Default model `whisper-large-v3-turbo`. |
| **OpenAI** | API key | Default model `whisper-1`. `gpt-4o-transcribe` also works, without word timings. |
| **Deepgram** | API key from [console.deepgram.com](https://console.deepgram.com/) | Default model `nova-3`. |
| **Local Whisper server** | Nothing, or the server's URL and model | Nothing leaves your computer. Default `http://localhost:8000/v1` (Speaches, faster-whisper-server, LocalAI). |
| **Custom (OpenAI-compatible)** | Server URL, model, and a key if it needs one | Any server with an OpenAI-style `/audio/transcriptions` endpoint, local or cloud. |
| **Audio chat model (OpenAI-compatible)** | Server URL, model, and a key if it needs one | A multimodal chat model (Gemini, gpt-4o-audio) behind an OpenAI-compatible gateway such as LiteLLM. The audio goes to `/chat/completions`. No word timings. |

Leave **Server URL** and **Model** blank to use the provider's defaults. Press **Test Connection** to
check what is on screen (it applies the values first). The window shows which provider is in use next to **Speech settings...**.

Set **My language** and **Their language** in the window (or leave them on Auto-Detect). Naming the
language noticeably improves accuracy on short lines, and you and the people on the call often speak
different ones.

Word-level replay needs word timings, which Groq, `whisper-1`, Deepgram and most local Whisper servers
report. Without them everything else still works; only **Play** on a single word is missing.

### Live text: Built-in Vosk (offline, no Docker or Python)

With an accurate provider alone, a line appears only after its speaker pauses and the provider
answers, which with a slow model can take 20 seconds. **Live text** shows words while people are still
talking, using [Vosk](https://alphacephei.com/vosk/). Words appear about one second after they are spoken,
completely offline with zero data leaving your machine.

QTranslate includes Vosk's native offline recognition engine directly inside the Speech Recognition plugin:

1. In **Settings > Plugins > Speech Recognition**, under **Live text**, set **Live engine** to **Vosk (built-in)**.
2. Select a model from **Built-in model** (for example, `vosk-model-small-en-us-0.15` for English ~40MB, or `vosk-model-ar-mgb2-0.4` for Arabic ~318MB).
3. Click **Download Model** to download and install the model into local storage directly from alphacephei.com. A progress window shows real-time transfer speed, remaining time, and supports pausing, resuming, or cancelling.
4. Click **Check Models** anytime to inspect installed models on disk.

No Docker, Python, or extra background processes are required.

**Modes:**
- **Local only**: Speech is transcribed completely offline with Vosk. Nothing leaves your machine, and cloud provider fields are hidden.
- **Cloud only**: Transcribes line by line through your chosen cloud provider.
- **Hybrid**: Shows instant live text from Vosk, and refines each finished line with the accurate provider.

**Important local model notes:**
- **Model requirements & missing models**: When running offline or with Vosk live text, a model must be installed for each spoken language in the session. Languages without an installed model are tagged as `(model not installed)` in the language dropdowns. If you start a recording without a required model, QTranslate warns you and offers to open Speech settings.
- **RAM usage**: Small models (e.g. `small-en-us ~40MB`, `small-de ~45MB`) take ~150MB RAM and run smoothly on any machine. Large models (e.g. `ar-0.22-linto ~1.3GB`) require ~1.8GB–3GB RAM. Choose small models on memory-constrained systems.
- **Recovering damaged models**: If a download or extraction was interrupted, the model status will indicate `incomplete, delete and download again`. Simply click **Delete Model** in Speech settings and then click **Download Model** to reinstall cleanly.
- **"Auto" language in local mode**: Unlike cloud models, local Vosk does not perform multi-language auto-detection on the fly. In offline mode, "Auto" uses the selected model's language (or the system language when available). If a session uses a different language and a matching model is installed, QTranslate switches to it automatically.

### 2. The call's audio

On Windows, the **Call audio** picker lists your output devices, and QTranslate records what each one
plays through WASAPI loopback. No driver or extra software is needed.

- Pick the output Discord plays to. Recording your usual headphones captures everything they
  play: the call, but also games, music and videos.
- For the call alone, give Discord an output of its own. **SteelSeries Sonar** already has one
  (**Sonar - Chat**), and QTranslate preselects it when it finds it. Otherwise, set Discord's
  **Output Device** (User Settings > Voice & Video) to a separate device, or to a virtual cable such
  as VB-Cable, and pick that here.

On macOS and Linux, loopback is not available. Route the call through a virtual input device
(BlackHole on macOS, a PulseAudio monitor source on Linux) and pick it under **Call audio**.

### 3. Headphones

Use headphones. With speakers, your microphone also picks up the call, and every line from the
call is transcribed twice.

## Privacy

- Recordings and transcripts are stored only on this computer, in the `conversations` folder of
  QTranslate's data directory: one folder per session, holding `session.json` (the transcript)
  and `microphone.wav` / `system.wav` (the recordings).
- To transcribe, each spoken clip is sent to the speech provider you configured. With a local
  Whisper server, nothing leaves your computer.
- Hover translations go to your active translator, and meanings to your active dictionary, as
  everywhere else in QTranslate.
- Untick **Keep recordings after the session** to keep only the transcript: recordings are
  deleted when the session ends. Replay and retry are then unavailable for that session.
- Tell the people on the call that you are recording. Recording a conversation without
  everyone's consent is illegal in many places.

## What to expect

- **Delay.** Without a live recognizer, a line appears once its speaker pauses and the provider
  answers: a second or two with Groq, 20 seconds or more with a multimodal chat model. While a clip is
  being transcribed, its line shows *Transcribing...*. With Vosk, words appear as they are spoken.
- **Source, not speaker.** "Call" is everyone on the call mixed together; QTranslate does not tell
  your friends apart.
- **Failures don't lose audio.** If the speech provider is down, out of credit, or not set up, the
  recording carries on and the affected lines say *Could not transcribe this part*. Click one to
  retry it, or press **Retry failed** to retry them all once the provider works.
- **Devices.** If a device is unplugged mid-session, only its stream stops; its light turns red,
  and the other stream keeps recording.
- **Crashes.** If QTranslate closes unexpectedly while recording, the session is marked
  *interrupted* the next time it starts, and the audio up to that moment is kept and playable.
- **Pausing** stops both streams together. Nothing said while paused is recorded or sent anywhere.
- **Disk use.** About 2 MB per minute for each stream.
