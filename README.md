# AI UI

A personal Android chat client for any model endpoint. Each **profile** is a base URL, an API key and an API format: Anthropic, OpenAI, Nebius and similar platforms, or Ollama and LM Studio on your own network. Pick a profile, then one of its models, and chat.

- Design doc: https://claude.ai/artifact/QXrh5dE9gocNu9ETSBfdZ6
- UI sketches: https://claude.ai/artifact/VNjxxwUJ8YZ2irVQUCW5Wj

## Build and install

Requires Android Studio (or its bundled JDK 21) and Android SDK 36.

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleRelease          # app/build/outputs/apk/release/app-release.apk (about 4 MB)
adb install -r app/build/outputs/apk/release/app-release.apk
./gradlew :app:testDebugUnitTest        # unit tests
```

Release builds are signed with the debug key so they install over debug builds. Create a real signing key before sharing the APK with anyone.

## Connecting to your computer

| Server | Base URL | On the computer |
| --- | --- | --- |
| Ollama | `http://<computer-ip>:11434/v1` | Start with `OLLAMA_HOST=0.0.0.0 ollama serve` |
| LM Studio | `http://<computer-ip>:1234/v1` | Developer tab: start the server and turn on Serve on Local Network |

Plain `http://` is allowed only to private addresses (10.x, 172.16-31.x, 192.168.x, Tailscale's 100.64-127.x) and `.local` names. Anything else needs HTTPS, or the profile's "Allow insecure connection" switch.

## Layout

| Path | What it holds |
| --- | --- |
| `llm/` | `LlmClient`, the Anthropic and OpenAI-compatible adapters, SSE parsing, the LAN-only HTTP rule |
| `data/` | Room database, `ChatRepository` (streaming, retries, history), `ProfileRepository`, `KeyVault`, `AttachmentStore`, preferences |
| `ui/chat/` | Conversation screen, composer, drawer, markdown renderer |
| `ui/components/` | The two-step profile and model picker |
| `ui/profiles/`, `ui/settings/`, `ui/personalization/` | The other screens |

Anthropic requests send `anthropic-version: 2026-10-21` and replay each reply's content blocks verbatim (thinking blocks included) while a chat stays on the same profile and model. A chat's system prompt is fixed when the chat starts.
