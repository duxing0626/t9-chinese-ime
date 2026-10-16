#!/bin/bash
# Downloads Android build-tools 36 and the API 30 android.jar into .sdk/ (macOS or Linux).
set -euo pipefail
cd "$(dirname "$0")/.."

case "$(uname -s)" in
  Darwin) BT_ZIP=build-tools_r36_macosx.zip ;;
  Linux) BT_ZIP=build-tools_r36_linux.zip ;;
  *) echo "Unsupported OS; install Android build-tools 36 into .sdk/build-tools manually." >&2; exit 1 ;;
esac
REPO=https://dl.google.com/android/repository

mkdir -p .sdk
cd .sdk
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

if [ ! -x build-tools/aapt2 ]; then
  echo "Downloading $BT_ZIP ..."
  curl -fsSL -o "$tmp/bt.zip" "$REPO/$BT_ZIP"
  unzip -q "$tmp/bt.zip" -d "$tmp/bt"
  rm -rf build-tools
  mv "$tmp/bt/"* build-tools
fi
if [ ! -f platform/android.jar ]; then
  echo "Downloading platform-30 ..."
  curl -fsSL -o "$tmp/pf.zip" "$REPO/platform-30_r03.zip"
  unzip -q "$tmp/pf.zip" -d "$tmp/pf"
  rm -rf platform
  mv "$tmp/pf/"* platform
fi
echo "SDK ready in .sdk/"
