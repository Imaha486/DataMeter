#!/usr/bin/env bash
# DataMeter ビルドスクリプト（Gradle不要）
#  - ANDROID_HOME（Android SDK）があればそれを使用
#  - なければ tools/fetch-tools.sh で取得したツール（.tools/）を使用
#  署名鍵: 環境変数 KEYSTORE（既定 release.jks）/ KS_PASS / KEY_ALIAS
set -euo pipefail
cd "$(dirname "$0")"

KEYSTORE=${KEYSTORE:-release.jks}
KS_PASS=${KS_PASS:-datameter}
KEY_ALIAS=${KEY_ALIAS:-key}
API=34

if [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME/platforms/android-$API" ]; then
  BT=$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)
  AAPT2="$BT/aapt2"
  AJAR="$ANDROID_HOME/platforms/android-$API/android.jar"
  D8_CMD=(java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8)
  SIGNER=(java -jar "$BT/lib/apksigner.jar")
else
  [ -d .tools ] || bash tools/fetch-tools.sh
  AAPT2=.tools/aapt2
  AJAR=.tools/android.jar
  D8_CMD=(java -cp .tools/d8.jar com.android.tools.r8.D8)
  SIGNER=(java -jar .tools/apksigner.jar)
fi
echo "aapt2: $AAPT2"; echo "android.jar: $AJAR"

rm -rf build && mkdir -p build/{res,gen,classes,dex}

"$AAPT2" compile --dir res -o build/res/res.zip
"$AAPT2" link -o build/base.apk -I "$AJAR" --manifest AndroidManifest.xml \
  --java build/gen build/res/res.zip --min-sdk-version 29 --target-sdk-version $API

# --release 8 : JDK21のjavacが出力するMethodParameters属性でd8が落ちるのを避けるため
javac --release 8 -cp "$AJAR" -encoding UTF-8 -nowarn \
  -d build/classes $(find src build/gen -name '*.java')
"${D8_CMD[@]}" --release --min-api 29 --lib "$AJAR" \
  --output build/dex $(find build/classes -name '*.class')

cp build/base.apk build/unsigned.apk
(cd build/dex && zip -q -X ../unsigned.apk classes.dex)
python3 zipalign.py build/unsigned.apk build/aligned.apk

if [ ! -f "$KEYSTORE" ]; then
  echo "署名鍵 $KEYSTORE が無いため新規作成します（以前のAPKとは別署名になります）"
  keytool -genkeypair -keystore "$KEYSTORE" -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -alias "$KEY_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=DataMeter"
fi
"${SIGNER[@]}" sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --ks-key-alias "$KEY_ALIAS" \
  --out build/DataMeter.apk build/aligned.apk
"${SIGNER[@]}" verify build/DataMeter.apk
ls -la build/DataMeter.apk
