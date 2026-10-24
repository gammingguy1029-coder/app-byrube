# RizzBoard AI — Android IME source project

RizzBoard is a native Android application with a real `InputMethodService` keyboard and a Jetpack Compose companion settings app. It is not a web app or a keyboard mockup. This workspace contains the Android source project and a built, debug-signed APK at `deliverables/RizzBoardAI.apk`.

## Build environment inspected

The build environment initially had OpenJDK 11, no system Gradle, and no Android SDK. I installed a temporary JDK 17 and Android SDK Platform 35 / Build Tools, used the included Gradle 8.10.2 wrapper, and built the debug APK successfully. The first dependency download needed Java TLS 1.2 explicitly; the Gradle configuration is memory-bounded for this workspace. `apksigner` verified the APK signature. The APK is `deliverables/RizzBoardAI.apk`.

Build configuration: Android Gradle Plugin 8.8.2, Java 17, compile/target SDK 35, min SDK 29 (Android 10). The APK build succeeded, but I did not run an emulator/device instrumentation test in this workspace. For a local rebuild, use Android Studio with JDK 17 and Android SDK Platform 35 / Build Tools installed; `./gradlew :app:assembleDebug` produces `app/build/outputs/apk/debug/app-debug.apk`.

## Build and install

1. Open this directory in Android Studio (current stable; JDK 17).
2. Install Android SDK Platform 35 and matching build tools when prompted.
3. Sync Gradle and run the `app` configuration on a device/emulator. From a terminal, `./gradlew :app:assembleDebug` produces `app/build/outputs/apk/debug/app-debug.apk` when dependencies and SDK are available.
4. In RizzBoard AI, tap **Enable keyboard** to open Android's input method settings. Enable RizzBoard AI, then use **Choose keyboard** or the keyboard switcher while focused in a text field.
5. To use AI, create a provider configuration (see below). Normal typing works without configuring any provider.

The configured platform range is Android 10 (API 29) through Android 15 (API 35). The app is Kotlin/Java-only with no native libraries or ABI filters, so the APK is architecture-neutral and runs on 64-bit ARM devices (including `arm64-v8a`) through Android Runtime. No API-29+ only platform calls are used without platform support; still test each vendor keyboard integration. The first installed app launch needs no account. On a Redmi Note 14 / Android 15, Android's Settings and keyboard-switcher UI may differ slightly by OS build.

## Included functionality

- Native IME with alphabetic and number/symbol layouts, shift, delete, space, punctuation, enter/editor actions, mode menu, and a compact AI panel.
- Twelve data-driven writing modes, each with a separate instruction template.
- Selected text is preferred (selections over 4,000 characters are refused). When no selection is exposed by an editor, the IME uses only text immediately before the cursor (up to 4,000 characters). It never reads the whole conversation or scrapes other apps.
- Preview before insertion; explicit Apply, Regenerate, Discard, and restore-original controls. Insertions use `InputConnection`; replacing a snapshot is refused if the text at the cursor no longer matches, to avoid clobbering changed content. Some apps deliberately restrict selection or cursor context, so behavior can vary.
- A companion settings UI for provider CRUD, choosing a default, enabling/disabling a provider, a connection test, mode and creativity preferences.
- OpenAI-compatible Chat Completions adapter using OkHttp and coroutines. An adapter interface is provided for future API formats.
- API keys and optional custom headers are AES/GCM-encrypted in app-private preferences using a non-exportable Android Keystore key. Provider metadata and general preferences are app-private. Android backup is disabled.

## Provider endpoint convention

The initial adapter supports **only the OpenAI Chat Completions request/response format**, not every service that happens to expose an API. Configure either a base URL, for example `https://api.example.com/v1`, or the complete endpoint `https://api.example.com/v1/chat/completions`. A `/chat/completions` path is appended to a base URL. Configure the provider's exact model ID and API key. HTTPS is required; the app does not silently fall back to HTTP. Optional headers use one `Name: value` per line. Custom `Authorization` headers are ignored because the adapter uses the API key as a Bearer token.

The app sends only the supplied message text and a mode prompt to the endpoint you select. **A user-controlled endpoint or AI provider may log submitted text** under its own terms and retention policies. Do not configure an endpoint you do not trust. No API key, endpoint, or provider secret is baked into the source or BuildConfig; there is no third-party proxy.

## QA checklist for a device

- Enable the IME and type/delete/shift, switch to symbols, use punctuation and space, submit a newline, and test search/send/done editor actions.
- Test an ordinary text field, an Instagram DM, a long message, a selected phrase, a cursor in the middle of text, and a field where selection is unavailable. Verify the original text is preserved until Apply.
- Configure a valid provider and test it; verify success, invalid-key / 401, missing-model / 404, 429, unavailable network, timeout, malformed response, empty response, cancellation, and disabling/deleting the active provider.
- Apply a result, edit it in the target field, and verify restore-original refuses to overwrite text that has since changed.
- Confirm API key and custom headers do not appear in logs, crash reports, exported backups, or cleartext requests.
- Test rotation, activity recreation, returning from input-method settings, and keyboard switching across apps.

## Known limitations / production follow-up

- The debug APK compiled and passed APK signature verification, but it has not been installed or exercised on the Redmi Note 14 / Android 15 in this workspace. Run the device QA checklist before release.
- The keyboard is a usable initial IME rather than a full replacement for a mature system keyboard: no swipe typing, dictionary, autocorrect, multilingual layouts, or configurable key haptics yet.
- Target apps control what an IME can observe. If an app returns no selected text/context, AI rephrasing will be unavailable or will conservatively refuse to replace a stale snapshot.
- Provider test requests a tiny fixed test prompt. It does not include user message content.
- Consider adding automated UI/instrumentation tests, certificate pinning policy where appropriate, a richer accessibility review, privacy disclosures, localization, and a provider adapter conformance suite before publication.
