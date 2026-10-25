# AGENTS.md — RizzBoard AI / "Rube Rizz" (Android IME)

Single-module Android app (`:app`): an `InputMethodService` keyboard, a Jetpack
Compose settings activity, and an OpenAI-compatible AI adapter. Verified facts
only — trust build/CI config over README.md, whose build-environment section is
stale (it describes a one-off local SDK install that no longer exists here).

## Build & verification (CI-only — do not build locally)

- This Windows workspace has **no JDK and no Android SDK** (`java` not on PATH,
  no `local.properties`, no `ANDROID_HOME`). Local `gradlew` runs will fail.
  The only verification path is GitHub Actions.
- Loop used and working: edit → commit → `git push` → poll with `gh`:
  ```powershell
  $id = ((gh run list --repo gammingguy1029-coder/app-byrube --limit 1 --json databaseId | ConvertFrom-Json)[0]).databaseId
  gh run watch $id --repo gammingguy1029-coder/app-byrube --compact --exit-status
  ```
  `gh` is authenticated (owner). **Do not poll the runs API with
  `Invoke-WebRequest` loops** — one such loop hung > 25 min; `gh` is reliable.
- CI (`.github/workflows/build.yml`) runs **only `:app:assembleDebug`** — green
  build proves compilation and nothing else (no lint, no tests exist).
- Pushes touching only `*.md`/`.gitignore` do **not** trigger CI (paths-ignore).
- Failure logs get pushed to the `ci-logs` branch; `gh run view <id> --log-failed`
  also works (anonymous API log download is 403).
- APK artifact: `RizzBoardAI-debug-apk` (30-day retention). The `release` job
  only fires on `v*` tags.

## Windows/PowerShell gotchas here

- `git add/commit/push` write progress to stderr; piping through
  `2>&1 | Out-String` under PowerShell shows scary `NativeCommandError` blocks.
  Harmless — judge success by the ref-update line / `$LASTEXITCODE`.
- No unix utils (`head` etc.); use `Select-Object -First N`.

## Non-negotiable constraints

- **Never change `applicationId`/package `ai.rizzboard`.** The user installs CI
  APKs over previous ones; changing it creates a second app and loses settings.
- Do bump `versionCode` + `versionName` in `app/build.gradle.kts` whenever the
  user will install the build (same-versionCode reinstalls are flaky on OEM
  package installers).
- Display name was recently renamed to **"Rube Rizz"** (manifest labels, UI
  strings, AI-system-prompt persona), while internal identifiers still say
  RizzBoard (`theme name`, `RizzBoardApp`, package). That asymmetry is
  intentional — don't "fix" it and don't rename the package to match.

## Architecture notes (not obvious from filenames)

- `ime/RizzInputMethodService.kt` builds the entire keyboard programmatically —
  no XML layouts. `render()` rebuilds the view tree; layout switches
  (letters/symbols/emoji) are visibility toggles on prebuilt row-hosts.
- The per-keystroke path must never rebuild views: keys commit via
  `InputConnection` directly and only cached views (`shiftKey`, `qKey`,
  `spaceKey`, `enterKey`, `emojiKey`) update in place. Preserving this pattern
  is why typing is fast; don't route toggles through `render()`.
- Provider API keys are AES/GCM-encrypted with AndroidKeyStore
  (`data/ProviderStore.kt`). `saveProvider` returns `Boolean` and the Compose
  editor keeps the dialog open with the error on failure — keep `encrypt()`
  inside that try/catch.
- The AI adapter is OpenAI Chat Completions **only**; `endpoint()` normalizes
  base URLs to `/v1/chat/completions` and enforces HTTPS. One shared
  `OkHttpClient` (`clientFor()`); per-request client creation was a fixed perf
  bug — don't reintroduce it.
- The emoji page is a fixed 39-slot grid (4 rows × 10, one slot taken by ⌫).
  Adding an emoji means swapping one out or restructuring rows.

## Regressions already fixed once — don't undo

- Letter keys are always-uppercase (iOS-style); Shift state is a single-view
  highlight. An earlier version relabeled all 26 letter views per capital.
- Key release is bounds-checked (slide-off cancels) and routed via
  `performClick()` for accessibility.
- Backspace deletes whole grapheme clusters (variation selectors, ZWJ chains,
  skin tones, flag pairs) — don't simplify to fixed-length deletion.
- The `?123`/`ABC` bottom-left key must be labeled to match where tapping it
  actually goes (on the emoji page it returns to letters, labeled `ABC`).

## Known-unverified areas

- The nav-bar inset handling in `onCreateInputView` (edge-to-edge padding from
  `WindowInsetsCompat`) has never been device-verified (target device: Redmi
  Note 14, HyperOS Android 15). Do not blind-refactor it; ask the user to test.
- Zero tests exist in the repo; any behavioral proof requires CI + user device.
