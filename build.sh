#!/usr/bin/env bash
# Build "Chromium uBlock": a portable Chromium with uBlock Origin Lite built in.
#
# Linux and macOS. Produces dist/chromium-ublock/ with:
#   chromium/                 the browser (downloaded snapshot, or a symlink to yours)
#   extensions/ublock/        uBlock Origin Lite, unpacked
#   chromium-ublock           launcher that always starts Chromium with uBO loaded
#
# Usage: ./build.sh [--chromium PATH] [--revision N] [--ublock-zip FILE] [--out DIR] [--install]
set -euo pipefail

SNAPSHOTS="https://commondatastorage.googleapis.com/chromium-browser-snapshots"
UBOL_API="https://api.github.com/repos/uBlockOrigin/uBOL-home/releases/latest"
UBOL_GIT="https://github.com/uBlockOrigin/uBOL-home"

ROOT="$(cd "$(dirname "$0")" && pwd)"
OUT="$ROOT/dist/chromium-ublock"
CHROMIUM_BIN=""
REVISION=""
UBLOCK_ZIP=""
INSTALL=0

usage() { sed -n '2,11p' "$0" | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }
log() { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
die() { printf '\033[1;31merror:\033[0m %s\n' "$*" >&2; exit 1; }

while [ $# -gt 0 ]; do
  case "$1" in
    --chromium)   CHROMIUM_BIN="$2"; shift 2 ;;
    --revision)   REVISION="$2"; shift 2 ;;
    --ublock-zip) UBLOCK_ZIP="$2"; shift 2 ;;
    --out)        OUT="$2"; shift 2 ;;
    --install)    INSTALL=1; shift ;;
    -h|--help)    usage 0 ;;
    *)            echo "unknown option: $1" >&2; usage 1 ;;
  esac
done

for t in curl unzip; do command -v "$t" >/dev/null || die "'$t' is required"; done

case "$(uname -s)-$(uname -m)" in
  Linux-x86_64)               PLATFORM=Linux_x64; ZIP=chrome-linux.zip; EXE=chrome-linux/chrome ;;
  Darwin-arm64)               PLATFORM=Mac_Arm;   ZIP=chrome-mac.zip;   EXE=chrome-mac/Chromium.app/Contents/MacOS/Chromium ;;
  Darwin-x86_64)              PLATFORM=Mac;       ZIP=chrome-mac.zip;   EXE=chrome-mac/Chromium.app/Contents/MacOS/Chromium ;;
  *) [ -n "$CHROMIUM_BIN" ] || die "no prebuilt Chromium for $(uname -sm); pass --chromium /path/to/chromium" ;;
esac

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
rm -rf "$OUT"
mkdir -p "$OUT/extensions"

# ---------------------------------------------------------------- Chromium
if [ -n "$CHROMIUM_BIN" ]; then
  CHROMIUM_BIN="$(cd "$(dirname "$CHROMIUM_BIN")" && pwd)/$(basename "$CHROMIUM_BIN")"
  [ -x "$CHROMIUM_BIN" ] || die "not executable: $CHROMIUM_BIN"
  log "Using existing Chromium: $CHROMIUM_BIN"
  mkdir -p "$OUT/chromium"
  ln -s "$CHROMIUM_BIN" "$OUT/chromium/chromium"
  BROWSER_REL="chromium/chromium"
else
  [ -n "$REVISION" ] || REVISION="$(curl -fsSL "$SNAPSHOTS/$PLATFORM/LAST_CHANGE")"
  log "Downloading Chromium $PLATFORM snapshot r$REVISION"
  curl -fL --progress-bar -o "$WORK/$ZIP" "$SNAPSHOTS/$PLATFORM/$REVISION/$ZIP" \
    || die "no snapshot at r$REVISION; try another --revision"
  unzip -q "$WORK/$ZIP" -d "$WORK/chromium"
  mv "$WORK/chromium/${EXE%%/*}" "$OUT/chromium"
  BROWSER_REL="chromium/${EXE#*/}"
  # Unsigned download: clear Gatekeeper quarantine so macOS will open it.
  [ "$(uname -s)" = Darwin ] && xattr -cr "$OUT/chromium" 2>/dev/null || true
  echo "$REVISION" > "$OUT/chromium/REVISION"
fi

# ---------------------------------------------------------------- uBlock Origin Lite
UBO="$OUT/extensions/ublock"
if [ -z "$UBLOCK_ZIP" ]; then
  log "Looking up latest uBlock Origin Lite release"
  URL="$(curl -fsSL "$UBOL_API" 2>/dev/null \
        | grep -o '"browser_download_url": *"[^"]*chromium[^"]*\.zip"' \
        | head -n1 | sed 's/.*"\(https[^"]*\)"/\1/' || true)"
  if [ -n "$URL" ]; then
    log "Downloading $(basename "$URL")"
    curl -fL --progress-bar -o "$WORK/ubol.zip" "$URL" && UBLOCK_ZIP="$WORK/ubol.zip"
  fi
fi

if [ -n "$UBLOCK_ZIP" ]; then
  unzip -q "$UBLOCK_ZIP" -d "$WORK/ubol"
  MANIFEST="$(find "$WORK/ubol" -maxdepth 3 -name manifest.json | head -n1)"
  [ -n "$MANIFEST" ] || die "no manifest.json in $UBLOCK_ZIP"
  mv "$(dirname "$MANIFEST")" "$UBO"
else
  command -v git >/dev/null || die "GitHub releases unreachable and git not installed"
  log "Release download unavailable; fetching uBlock Origin Lite from git"
  git clone -q --depth 1 "$UBOL_GIT" "$WORK/ubol-git"
  mv "$WORK/ubol-git/chromium" "$UBO"
fi
[ -f "$UBO/manifest.json" ] || die "uBlock Origin Lite install failed"
UBO_VERSION="$(grep -o '"version": *"[^"]*"' "$UBO/manifest.json" | head -n1 | sed 's/.*"\([^"]*\)"$/\1/')"
log "uBlock Origin Lite $UBO_VERSION"

# ---------------------------------------------------------------- launcher
cat > "$OUT/chromium-ublock" <<EOF
#!/usr/bin/env bash
# Starts Chromium with uBlock Origin Lite always loaded.
# Profile: \$CHROMIUM_UBLOCK_PROFILE (default ~/.config/chromium-ublock).
HERE="\$(cd "\$(dirname "\$(readlink -f "\$0" 2>/dev/null || echo "\$0")")" && pwd)"
PROFILE="\${CHROMIUM_UBLOCK_PROFILE:-\$HOME/.config/chromium-ublock}"
mkdir -p "\$PROFILE"
exec "\$HERE/$BROWSER_REL" \\
  --user-data-dir="\$PROFILE" \\
  --load-extension="\$HERE/extensions/ublock" \\
  --disable-features=DisableLoadExtensionCommandLineSwitch \\
  --no-default-browser-check \\
  "\$@"
EOF
chmod +x "$OUT/chromium-ublock"
cp "$UBO/img/icon_128.png" "$OUT/icon.png" 2>/dev/null || true

# ---------------------------------------------------------------- optional desktop entry
if [ "$INSTALL" = 1 ]; then
  [ "$(uname -s)" = Linux ] || die "--install is Linux-only (on macOS, run the launcher or wrap it with Automator)"
  APPS="${XDG_DATA_HOME:-$HOME/.local/share}/applications"
  mkdir -p "$APPS" "$HOME/.local/bin"
  ln -sf "$OUT/chromium-ublock" "$HOME/.local/bin/chromium-ublock"
  cat > "$APPS/chromium-ublock.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=Chromium uBlock
GenericName=Web Browser
Comment=Chromium with uBlock Origin Lite built in
Exec="$OUT/chromium-ublock" %U
Icon=$OUT/icon.png
Terminal=false
Categories=Network;WebBrowser;
MimeType=text/html;x-scheme-handler/http;x-scheme-handler/https;
StartupWMClass=chromium-browser
EOF
  log "Installed menu entry and ~/.local/bin/chromium-ublock"
fi

log "Done: $OUT/chromium-ublock"
