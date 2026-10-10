#!/usr/bin/env bash
# Android SDK が無い環境向け：npm レジストリ上のパッケージからビルドツールを取得して .tools/ に置く
# （aapt2: aaptjs3 / android.jar・d8.jar・apksigner.jar: @drxiaozhi/minapk）
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .tools/tmp
curl -sL https://registry.npmjs.org/aaptjs3/-/aaptjs3-2.0.2.tgz | tar xz -C .tools/tmp
mkdir -p .tools/tmp/minapk
curl -sL https://registry.npmjs.org/@drxiaozhi/minapk/-/minapk-0.4.0.tgz | tar xz -C .tools/tmp/minapk
cp .tools/tmp/package/bin/x64/linux/aapt2 .tools/aapt2 && chmod +x .tools/aapt2
for f in android.jar d8.jar apksigner.jar; do cp .tools/tmp/minapk/package/tools/$f .tools/; done
rm -rf .tools/tmp
echo "tools ready in .tools/"
