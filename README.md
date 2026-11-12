# AI UI

A personal Android chat client for any model endpoint. Each **profile** is a base URL, an API key and an API format: Anthropic, OpenAI, Nebius and similar platforms, or Ollama and LM Studio on your own network. Pick a profile, then one of its models, and chat.

Everything stays on the phone: no account, no backend, no analytics. API keys are encrypted with an Android Keystore key, and the only network traffic goes to the endpoints you add.

## Build and install

Requires Android Studio (or its bundled JDK 21) and Android SDK 36.

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleRelease          # app/build/outputs/apk/release/app-release.apk (about 4 MB)
adb install -r app/build/outputs/apk/release/app-release.apk
./gradlew :app:testDebugUnitTest        # unit tests
```

Local release builds are signed with the debug key. Debug builds install as `dev.aiui.debug`, next to the released app.

## Releases

Push a tag `vMAJOR.MINOR.PATCH` (each 0-99) and the Release workflow publishes a GitHub Release with the signed APK and its SHA-256. The tag sets `versionName`, and `versionCode` is `MAJOR*10000 + MINOR*100 + PATCH`.

```sh
git tag v0.1.0 && git push origin v0.1.0
```

CI runs the unit tests on every pull request and push to `main`. To build a debug APK from any branch, run the CI workflow by hand (Actions → CI → Run workflow).

Signing needs three repository secrets: `KEYSTORE_BASE64` (the PKCS12 keystore, base64), `KEYSTORE_PASSWORD` and `KEY_ALIAS`.

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

## License

MIT. See [LICENSE](LICENSE).
