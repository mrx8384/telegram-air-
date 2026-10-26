# Telegram Channel Auto Manager

An Android app (Kotlin, Jetpack Compose, Material 3) that finds Telegram channels from names,
`@usernames` or links, and then — only after you confirm — previews, archives, leaves, or
permanently deletes channels you own.

It works in two ways:

| Mode | How it works | Needs |
|---|---|---|
| **Background** | Talks to Telegram through [TDLib](https://core.telegram.org/tdlib) with *your own* account, logged in through Telegram's official login. | API ID + API hash for the app (my.telegram.org), your Telegram login |
| **Screen** | Drives the official Telegram app through an `AccessibilityService`, with ML Kit OCR as a fallback for finding labels. | Official Telegram app, accessibility service turned on |
| **Auto** | Uses Background when you are logged in and it supports the action, otherwise Screen. It tells you which one it picked and why. | Either of the above |

> فارسی: این برنامه کانال‌های تلگرام را از روی نام، ‎@نام‌کاربری یا لینک پیدا می‌کند و فقط پس از تأیید شما
> آن‌ها را پیش‌نمایش، بایگانی، ترک یا (اگر مالک باشید) حذف می‌کند. رابط کاربری کاملاً فارسی و انگلیسی است.
> راهنمای نصب در بخش «Installation» آمده است.

---

## Features

**Channel input**
- `@username`, `t.me/name`, `https://t.me/s/name`, `name.t.me`, `tg://resolve?domain=name`
- Invite links: `t.me/+hash`, `t.me/joinchat/hash`, `tg://join?invite=hash`
- Plain channel titles (Persian and English; Arabic/Persian letter variants and digits are normalised)
- Many channels at once (one per line or comma separated), duplicates removed
- Import from a `.txt` file (see `samples/channels.txt`)

**Actions** — the app keeps these clearly apart:
- **Preview** — find and show details. Changes nothing.
- **Archive** — move the chat to the Archive folder (removes it from the main chat list). You stay subscribed. Background mode only.
- **Leave** — unsubscribe. The channel keeps existing for everyone else.
- **Delete owned channel** — permanently delete the channel for all subscribers. Owner only.

**Batch workflow** (a state machine in `core/…/BatchProcessor.kt`)
1. Read the list → 2. resolve each channel → 3. check membership/ownership → 4. show a preview →
5. you tick which tasks to run and confirm → 6. tasks run one by one → 7. each result is verified →
8. final report (also saved to History and shareable).

**Safety**
- **Dry run is on by default.** Nothing changes until you turn it off.
- Several matches → you must pick one. Partial name matches can never be acted on.
- Deleting needs a `@username`, link, or a channel you picked — never a title alone.
- **Every deletion needs its own confirmation**: you type the channel's `@username` (or exact title).
- Permissions are checked before the preview and again right before each action (live data from Telegram).
- Emergency stop (in the app, the notification and the floating panel).
- Rate limits: the app waits exactly as long as Telegram asks (up to 5 minutes), otherwise the task fails. It never tries to get around them.
- Screen mode never taps blindly: every tap targets a node or OCR text matching a known label. It re-checks the open channel's title/username before acting and stops if anything looks different.
- Detailed live log, stored in Room.

**Other**
- Telegram login: phone → code (with resend) → email code if asked → two-step-verification password. The phone, code and password go straight to TDLib and are not stored by the app.
- TDLib's database is encrypted with a random key kept in the Android Keystore; the stored API hash is encrypted the same way.
- Optional screen recording (MediaProjection → MP4 in `Movies/ChannelAutoManager`).
- Optional floating control panel (Start / Pause / Resume / Stop, current channel and step).
- Run history (Room).
- Dark theme, English + Persian (RTL), in-app language switch.

## Project layout

```
core/                      Pure Kotlin. Models, input parser, matcher, safety policy,
                           task state machine, batch processor, AUTO mode selector. Unit-tested.
automation-tdlib/          Background mode. TDLib session/login + TdlibChannelEngine.
                           Pure Kotlin/JVM, so it is tested against the real TDLib natives on desktop.
automation-accessibility/  Screen mode. AccessibilityService, UI driver, OCR, ScreenChannelEngine.
app/                       Compose UI, Room, Keystore, foreground services (automation,
                           screen recording, floating controls), dependency container.
```

Screen and background automation live in separate modules and only meet through the
`ChannelEngine` interface in `core`.

### TDLib integration

TDLib comes from [`dev.g000sha256:tdl-coroutines`](https://github.com/g000sha256/tdl-coroutines)
(Maven Central, Apache-2.0), version 16.0.0 = **TDLib 1.8.68**, which ships prebuilt native
libraries for Android (arm64-v8a, armeabi-v7a, x86, x86_64) and desktop. Only documented TDLib
functions are used: `setTdlibParameters`, `getAuthorizationState`, `setAuthenticationPhoneNumber`,
`checkAuthenticationCode`, `resendAuthenticationCode`, `setAuthenticationEmailAddress`,
`checkAuthenticationEmailCode`, `checkAuthenticationPassword`, `logOut`, `close`,
`searchPublicChat`, `checkChatInviteLink`, `searchChats`, `searchChatsOnServer`,
`searchPublicChats`, `getChat`, `getSupergroup`, `getSupergroupFullInfo`, `leaveChat`,
`deleteChat`, `addChatToList`. Their signatures were checked against the library's sources.

## Building

Requirements: JDK 17+ (built here with JDK 21), Android SDK with **platform 37** (`android-37.0`)
and build-tools 36. Gradle 9.8.2 is downloaded by the wrapper. Android Gradle Plugin 9.4.1, Kotlin 2.4.21.

```bash
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

# Optional: build-time Telegram API credentials (otherwise enter them in Settings)
cat > telegram.properties <<EOF
TELEGRAM_API_ID=123456
TELEGRAM_API_HASH=0123456789abcdef0123456789abcdef
EOF

./gradlew test            # all unit tests (core, TDLib, screen, app/Robolectric)
./gradlew assembleDebug   # debug APKs
```

APK output: `app/build/outputs/apk/debug/`
- `app-arm64-v8a-debug.apk` — most phones (~56 MB)
- `app-armeabi-v7a-debug.apk`, `app-x86_64-debug.apk`, `app-x86-debug.apk`
- `app-universal-debug.apk` — all CPU types (~98 MB)

Targets Android 10 (API 29) through Android 16 (targetSdk 36). It compiles against API 37 because
the current AndroidX libraries require it.

### Tests

| Module | What is tested |
|---|---|
| `core` (20) | link/username/title parsing, Persian normalisation, matching, safety rules, state machine, batch flow (dry run, per-deletion confirmation, ambiguity, rate-limit waits, failures, emergency stop), AUTO mode choice |
| `automation-tdlib` (3) | TDLib error mapping; a smoke test that loads the **real TDLib native library** and calls it offline |
| `automation-accessibility` (3) | label matching, subscriber counts, username extraction |
| `app` (4) | Robolectric: launches `MainActivity`, adds channels, starts without an engine, opens every tab, Persian UI |

## Installation

1. Copy `app-arm64-v8a-debug.apk` to the phone and open it (allow "Install unknown apps" for your file manager or browser).
2. **Background mode:**
   1. Go to <https://my.telegram.org> → *API development tools* and create an app to get an **API ID** and **API hash**.
   2. In the app: **Settings → Telegram API credentials** → save.
   3. **Account** tab → enter your phone number → code → (two-step password if you set one).
3. **Screen mode:**
   1. Install the official Telegram app and log in there.
   2. **Settings → Permissions → Accessibility service → Open** and turn on "Channel Auto Manager".
      On Android 13+ a sideloaded app may show this switch greyed out: open **App info → ⋮ → Allow restricted settings** first.
4. Optional: allow **Display over other apps** for the floating panel, and **Notifications** for progress.
5. On the **Channels** tab: add channels, pick the method and action, keep **Dry run** on for a first pass, tap **Find channels**, review, then **Confirm and run**.

## Permissions

| Permission | Why |
|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | TDLib talks to Telegram |
| `FOREGROUND_SERVICE` + `_DATA_SYNC` | keep a batch running while Telegram or another app is in front |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | optional screen recording |
| `FOREGROUND_SERVICE_SPECIAL_USE` | optional floating control panel |
| `POST_NOTIFICATIONS` | progress and Stop button |
| `SYSTEM_ALERT_WINDOW` | optional floating control panel |
| Accessibility service (user-enabled) | screen mode; limited to the official Telegram packages |
| Screen capture (asked each time) | optional screen recording |

## Known limitations

- **Not tested on a device or emulator.** The app builds, its unit tests pass, and Robolectric
  launches the UI, but no real Telegram account was used. Run with **Dry run** on first.
- **Screen mode depends on Telegram's UI.** Labels ("Leave channel", "Delete channel", "More options",
  "Edit", …) are matched in English and Persian. Other Telegram languages or a redesigned UI make
  screen mode stop with "Telegram's screen looked different" — it never guesses. Archiving is not
  available in screen mode. Telegram X and unofficial clients are not supported.
- **OCR fallback** needs Android 11+ (accessibility screenshots) and reads Latin script only
  (ML Kit's bundled model), so it does not help with a Persian Telegram UI.
- **Title search in screen mode** reads Telegram's search results. A row is treated as a channel
  only when it mentions "channel"/"subscribers"; otherwise it can only be previewed.
- **Verification**: leave/delete in screen mode can only be re-checked for channels with a public username.
  In background mode a deletion is reported "done, not verified" if Telegram accepted it but the chat still shows.
- Telegram requires an API ID/hash per app; none is bundled.
- New Telegram accounts (registration) and Premium-only logins must be finished in the official app.
- Engine messages in the live log are in English; the rest of the UI is translated.
- Debug builds are not minified, so they are large. A release build needs your own signing config.

## Third-party licenses

TDLib (BSL-1.0), tdl-coroutines (Apache-2.0), AndroidX/Compose (Apache-2.0) and ML Kit
(Google APIs Terms) keep their own licenses. No license has been chosen for this app's code yet.
