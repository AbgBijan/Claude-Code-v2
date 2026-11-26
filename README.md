# Chromium uBlock

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

## Options

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
