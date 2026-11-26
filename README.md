# Chromium uBlock (+ Azree Browser for Android)

A portable Chromium browser with **uBlock Origin Lite** built in. You don't have to
install anything from the Web Store. The ad blocker is part of the bundle and loads every time the
browser starts.

## Quick start

**Linux / macOS**

```bash
./build.sh --install        # --install adds an app-menu entry (Linux)
./dist/chromium-ublock/chromium-ublock
```

**Windows** (PowerShell)

```powershell
powershell -ExecutionPolicy Bypass -File .\build.ps1 -Install   # adds Desktop + Start menu shortcuts
& ".\dist\chromium-ublock\Chromium uBlock.cmd"
```

The build script:

1. Downloads the latest official Chromium snapshot for your platform
   (Linux x64, macOS Intel/Apple Silicon, Windows x64).
2. Downloads the latest uBlock Origin Lite release from
   [uBOL-home](https://github.com/uBlockOrigin/uBOL-home). If the release can't be
   downloaded, it gets the same files from the project's git repository instead.
3. Writes a launcher that always starts Chromium with uBO loaded, using its own
   profile (`~/.config/chromium-ublock`, or `%LOCALAPPDATA%\chromium-ublock` on Windows).

To update Chromium and uBO, run the build again. Your profile, bookmarks and settings
are kept.

## Android

Chromium on Android can't load extensions, so the `android/` folder has
**Azree Browser** instead: a small, standalone Android browser with ad blocking built in.
It uses uBlock Origin Lite's default filter lists: uBlock filters, EasyList, EasyPrivacy,
Peter Lowe's list, uBlock badware and URLhaus. That's about 93k blocked domains and 18k URL
rules.

- **Light:** about 1–2 MB, no third-party libraries, and it uses the phone's built-in
  Chromium WebView. Needs Android 10 or later.
- **Incognito:** open it from the ⋮ menu, by long-pressing a link, or by long-pressing the app
  icon. It runs in a separate process with its own cookies, storage and cache, so nothing
  is shared with normal browsing. It doesn't cache to disk or keep history. Screenshots
  and the recent-apps preview are blocked. Everything is wiped when you close it, and again
  on the next launch if Android killed it.
- **Shield:** shows how many ads and trackers were blocked on the page. Tap it to turn
  blocking off or on for that site.
- Also handles downloads, file uploads, full-screen video, and links to other apps
  (`tel:`, `mailto:`, …). Third-party cookies are blocked.

### Getting the APK

GitHub Actions builds it on every push that changes `android/`. Open **Actions → Android
APK**, choose the latest run and download `azree-browser-apk`. Copy the APK to the phone
and open it to install (allow "Install unknown apps" when asked).

To build it yourself, open `android/` in Android Studio, or run `./gradlew assembleRelease`
with the Android SDK installed. The APK is written to `android/app/build/outputs/apk/release/`.

### Updating the filter lists

Run the workflow manually with **Refresh filter lists** ticked, or:

```bash
git clone --depth 1 https://github.com/uBlockOrigin/uBOL-home /tmp/ubol
python3 android/tools/gen_filters.py /tmp/ubol/chromium/rulesets android/app/src/main/assets/filters.txt
```

### Limits compared with uBO on desktop

It blocks network requests (ads, trackers, malware domains). It doesn't do cosmetic
filtering (hiding empty ad boxes) or scriptlet injection, so some sites may show blank
gaps where ads were, and YouTube video ads aren't blocked reliably. If you need the
full uBlock Origin on Android, use Firefox for Android: it supports the real extension.

The filter data comes from uBlock Origin Lite (GPLv3) and the filter lists it bundles,
each under its own license. Azree Browser is not affiliated with uBlock Origin.

## Desktop options

| `build.sh`          | `build.ps1`   | Meaning                                         |
|---------------------|---------------|-------------------------------------------------|
| `--chromium PATH`   | `-Chromium`   | Use a Chromium you already have instead of downloading one |
| `--revision N`      | `-Revision`   | Use a specific Chromium snapshot revision         |
| `--ublock-zip FILE` | `-UblockZip`  | Use a local uBO Lite `.zip`                     |
| `--out DIR`         | `-Out`        | Output directory                                |
| `--install`         | `-Install`    | Add menu entries or shortcuts                   |

Set `CHROMIUM_UBLOCK_PROFILE` to use a different profile directory. Any extra arguments
you pass to the launcher go straight to Chromium.

## Why uBlock Origin *Lite*?

Chromium has dropped Manifest V2, the extension format the classic uBlock Origin
uses, so the classic version no longer runs in current Chromium. uBlock Origin Lite is
the same author's official Manifest V3 version for Chromium browsers. It uses the same
filter lists (uBlock filters, EasyList, EasyPrivacy, Peter Lowe's list and others) and
blocks ads and trackers well. It can't do some advanced things the classic version does,
such as dynamic filtering or writing your own filters.

Click the uBO icon in the toolbar to choose how strictly it filters a site. You can pick
the default mode and the filter lists on its settings page.

## Notes

- Chromium snapshots are unsigned developer builds and don't update themselves. They
  also leave out Google's proprietary codecs, so some H.264/AAC video may not play.
  Run the build again now and then to get security fixes.
- On macOS the script clears the download's quarantine flag so Gatekeeper will open it.
- Google Chrome (the branded build) ignores `--load-extension` since version 137.
  `--chromium` works with Chromium, Chrome for Testing, or Chromium-based builds that
  still honour that flag.

## Testing

On Linux x64, using a Chromium 158 binary through `--chromium`: the build put uBO Lite
2026.1124.1811 in place, and the launcher started the browser with the extension loaded.
Requests to `google-analytics.com` and `doubleclick.net` failed with
`ERR_BLOCKED_BY_CLIENT`, and the AdSense script was replaced with uBO's harmless
stand-in.
