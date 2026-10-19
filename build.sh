#!/data/data/com.termux/files/usr/bin/bash
# Gradle-free build that runs natively in Termux: aapt2 -> javac -> d8 -> pack/align -> apksigner.
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-$HOME/android-sdk}"
PLATFORM="$SDK/platforms/android-36/android.jar"
BUILD_TOOLS="$SDK/build-tools/37.0.0"
AAPT2="$(command -v aapt2)" # Termux's native aapt2; the SDK copy is x86_64-only
MIN_SDK=34
TARGET_SDK=36
VERSION_CODE=2
VERSION_NAME=1.1

KEYSTORE="${FIREWALL_KEYSTORE:-signing/firewall.jks}"
KEY_ALIAS="${FIREWALL_KEY_ALIAS:-firewall}"
KEY_PASS="${FIREWALL_KEY_PASS:-firewall}"

OUT=build
rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "> aapt2 compile"
"$AAPT2" compile --dir res -o "$OUT/res.zip"

echo "> aapt2 link"
"$AAPT2" link -I "$PLATFORM" \
    --manifest AndroidManifest.xml \
    --min-sdk-version "$MIN_SDK" --target-sdk-version "$TARGET_SDK" \
    --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
    --java "$OUT/gen" \
    -o "$OUT/base.apk" "$OUT/res.zip"

echo "> javac"
find src "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -source 8 -target 8 -Xlint:-options -encoding UTF-8 \
    -bootclasspath "$PLATFORM:$BUILD_TOOLS/core-lambda-stubs.jar" \
    -d "$OUT/classes" @"$OUT/sources.txt"

echo "> d8"
java -cp "$BUILD_TOOLS/lib/d8.jar" com.android.tools.r8.D8 \
    --release --min-api "$MIN_SDK" --lib "$PLATFORM" \
    --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

echo "> pack + align"
java tools/ApkPack.java "$OUT/base.apk" "$OUT/unsigned.apk" "$OUT/dex/classes.dex"

if [ ! -f "$KEYSTORE" ]; then
    echo "> creating signing key $KEYSTORE"
    mkdir -p "$(dirname "$KEYSTORE")"
    keytool -genkeypair -keystore "$KEYSTORE" -alias "$KEY_ALIAS" \
        -storepass "$KEY_PASS" -keypass "$KEY_PASS" \
        -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Firewall" >/dev/null
fi

echo "> apksigner"
java -jar "$BUILD_TOOLS/lib/apksigner.jar" sign \
    --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" \
    --ks-pass "pass:$KEY_PASS" --key-pass "pass:$KEY_PASS" \
    --out "$OUT/firewall.apk" "$OUT/unsigned.apk"
java -jar "$BUILD_TOOLS/lib/apksigner.jar" verify "$OUT/firewall.apk"

echo "Built $OUT/firewall.apk"
