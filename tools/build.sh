#!/bin/bash
# Builds dist/T9ChineseIME.apk without Gradle, using the build-tools and android.jar in .sdk/.
set -euo pipefail
cd "$(dirname "$0")/.."

SDK=.sdk
BT=$SDK/build-tools
JAR=$SDK/platform/android.jar
OUT=build
# Version numbers live in version.properties; bump both for each release.
VERSION_CODE=${VERSION_CODE:-$(sed -n 's/^versionCode=//p' version.properties)}
VERSION_NAME=${VERSION_NAME:-$(sed -n 's/^versionName=//p' version.properties)}

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" dist

if [ ! -x "$BT/aapt2" ] || [ ! -f "$JAR" ]; then
  tools/fetch_sdk.sh
fi
if [ ! -f app/res/drawable-xhdpi/ic_launcher.png ]; then
  java -Djava.awt.headless=true tools/GenIcons.java app/res
fi
if [ ! -f app/assets/jyutping.txt ]; then
  [ -f data/raw/emoji-test.txt ] || tools/fetch_data.sh
  python3 -I tools/build_data.py
fi

"$BT/aapt2" compile --dir app/res -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$JAR" \
  --manifest app/AndroidManifest.xml --java "$OUT/gen" -A app/assets \
  --min-sdk-version 21 --target-sdk-version 22 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  "$OUT/res.zip"

javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options -bootclasspath "$JAR" \
  -d "$OUT/classes" $(find app/src "$OUT/gen" -name '*.java')

"$BT/d8" --release --min-api 21 --lib "$JAR" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q ../unsigned.apk classes.dex)
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

# Signing key: kept out of git. Back it up -- updates must be signed with the same key.
KS=${T9IME_KEYSTORE:-tools/release.keystore}
PASS=${T9IME_KEYSTORE_PASS:-t9imepass}
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass "$PASS" -keypass "$PASS" -alias t9ime \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=T9 Chinese IME" >/dev/null
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$PASS" --key-pass "pass:$PASS" \
  --min-sdk-version 21 --out dist/T9ChineseIME.apk "$OUT/aligned.apk"
"$BT/apksigner" verify --min-sdk-version 21 dist/T9ChineseIME.apk
ls -la dist/T9ChineseIME.apk
