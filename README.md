# Papers Reader

A local-only Android app for reading and organizing scientific papers, built to feel like the
[Google Scholar PDF Reader](https://chromewebstore.google.com/) Chrome extension — but as a
proper Android library with browser tabs and on-device annotations.

## Features

- **Library** — import PDFs via the system file picker or "Share"/"Open with" from another app.
  Papers are stored under their real title (parsed from the PDF itself, PDF metadata + a
  font-size heuristic on page 1), never the original download filename.
- **Reader** — renders pages with Android's built-in Pdfium-backed renderer. Highlight text or
  drop a sticky note; annotations are saved locally per page.
- **References** — the bibliography is parsed out of the PDF; tap any reference to resolve it
  (via Crossref, with a relevance check to reject bad matches) and open it — falling back to a
  Google Scholar search, same as the Chrome extension, if nothing confident is found.
- **Browser** — a Chrome-like tab strip backed by real `WebView`s, so publisher pages behind
  Cloudflare/Akamai-style bot checks load exactly as they would in a normal mobile browser.
  "Save PDF to library" downloads the current tab straight into your library, carrying over
  its session cookies.
- **Logs** — a local, on-device log (plus last-crash trace) with a one-tap "Copy to clipboard",
  so a bug report is just paste-and-send — nothing leaves the device on its own.

Nothing syncs anywhere. Everything lives in the app's private storage on your phone.

## Requirements

- A JDK (21 recommended) — `sudo apt install openjdk-21-jdk` on Debian/Ubuntu.
- Android SDK platform 34 + build-tools (Android Studio installs these for you, or use
  `sdkmanager` directly).
- An Android device running Android 8.0 (API 26) or newer, or an emulator.

## Running it on your Android phone

### Option A — Android Studio (easiest)

1. Open this folder (`android_papers_reader/`) in Android Studio.
2. Let Gradle sync finish.
3. On your phone: **Settings → About phone**, tap **Build number** 7 times to unlock
   **Developer options**. Then **Settings → Developer options → USB debugging → on**.
4. Plug the phone into your computer with a USB cable. Accept the "Allow USB debugging?"
   prompt that appears on the phone (tick "always allow from this computer" if you want).
5. Your device should now appear in Android Studio's device dropdown (top toolbar). Select it
   and click **Run ▶**.

### Option B — command line, USB

```bash
# One-time: enable Developer options + USB debugging on the phone (steps 3–4 above),
# then plug it in and accept the "Allow USB debugging?" prompt.

adb devices        # should list your phone as "device" (not "unauthorized")
./gradlew installDebug
```

The app ("Papers Reader") will appear in your app drawer as usual.

### Option C — command line, no cable (wireless debugging, Android 11+)

1. On the phone: **Settings → Developer options → Wireless debugging → on**, then
   **Pair device with pairing code**.
2. On your computer:
   ```bash
   adb pair <ip>:<pairing-port>      # enter the 6-digit code shown on the phone
   adb connect <ip>:<port>           # IP:port shown on the phone's Wireless debugging screen
   adb devices                       # confirm it shows up
   ./gradlew installDebug
   ```

### Option D — just install the APK, no dev setup

If you don't want to deal with USB/wireless debugging at all:

```bash
./gradlew assembleDebug
```

This produces `app/build/outputs/apk/debug/app-debug.apk`. Copy that file to your phone
(email it to yourself, AirDrop/Nearby Share equivalent, USB file transfer, etc.), open it from
a file manager, and allow "install unknown apps" for that source when prompted. This has the
same effect as `installDebug` but doesn't require the computer and phone to be paired via adb.

## Sending me a bug report

If something goes wrong: open the app, tap the bug icon in the Library screen's top bar (or the
in-app **Logs** screen), tap **Copy logs** (or **Copy crash trace** if one is shown), and paste
the clipboard contents back here.

## Known limitations

- Pinch-to-zoom in the reader isn't implemented yet (page navigation is swipe-based).
- The browser's address bar is a plain omnibox — no autocomplete/history search.
- Reference resolution depends on Crossref's bibliographic index; not every citation style
  parses cleanly, and very obscure references may only resolve via the Scholar-search fallback.
