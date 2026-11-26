#!/usr/bin/env bash
# Builds the Windows app on Linux or macOS without Wine:
#   1. electron-builder packs the app (dist/win-unpacked), afterPack.js stamps the icon/version
#   2. NSIS (native makensis) builds the installer from installer/installer.nsi
#   3. dist/win-unpacked is zipped as a portable version
set -euo pipefail
cd "$(dirname "$0")"
VERSION=$(node -p "require('./package.json').version")
rm -rf dist
npx electron-builder --win dir --x64 --publish never
NSISDIR="${NSISDIR:-$HOME/.cache/electron-builder/nsis/nsis-3.0.4.1}"
if [ ! -x "$NSISDIR/linux/makensis" ] && ! command -v makensis >/dev/null; then
  echo "makensis not found: run 'npx electron-builder --win nsis' once to download NSIS, or install nsis" >&2; exit 1
fi
MAKENSIS=$( [ -x "$NSISDIR/linux/makensis" ] && echo "$NSISDIR/linux/makensis" || command -v makensis )
(cd installer && NSISDIR="$NSISDIR" "$MAKENSIS" -V2 -DVERSION="$VERSION" installer.nsi)
(cd dist && mv win-unpacked "PDF Master" && zip -qr "PDF-Master-Portable-$VERSION.zip" "PDF Master" && mv "PDF Master" win-unpacked)
ls -la dist/*.exe dist/*.zip
