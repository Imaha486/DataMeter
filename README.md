# DataMeter（データ通信量メーター）

Android の内部通信量カウンタ（`NetworkStatsManager`）から、月間のモバイルデータ通信量を直接取得して表示するアプリです。
キャリア系アプリ（My DATA Manager など）の値とズレる場合の確認用に作成。POCO X7 Pro（HyperOS / Android 15）で動作確認。

## 機能

- 集計期間（開始日を設定可能）のモバイル通信量：合計・受信・送信
- 月間上限に対する使用率、残り容量、残り日数から算出した 1日あたりの目安
- 今日の通信量、Wi‑Fi 通信量（参考）
- 日別棒グラフ
- アプリ別通信量（テザリング・削除済みアプリ・システムを含む）
- ホーム画面ウィジェット（横2×縦1、⟳ で手動更新、約15分ごとに自動更新）
- 1GB = 1000MB / 1024MB 切替

## 必要な権限

| 権限 | 用途 |
|---|---|
| `PACKAGE_USAGE_STATS`（使用状況へのアクセス） | 通信量カウンタの読み取り |
| `QUERY_ALL_PACKAGES` | アプリ別一覧でアプリ名を表示 |

### サイドロード時の「制限付き設定」解除

Android 13 以降、APK を直接インストールすると「使用状況へのアクセス」がグレーアウトします。

1. 使用状況へのアクセス画面でトグルを一度タップ（「アクセスを拒否されました」→ 閉じる）
2. 設定 → アプリ → データ通信量メーター → 右上「⋮」→「制限付き設定を許可」
3. 再度トグルをオン

ADB の場合: `adb shell appops set jp.local.datameter GET_USAGE_STATS allow`

## ビルド

Gradle は使わず、`aapt2` / `javac` / `d8` / `apksigner` を直接呼び出します。JDK 17 以上と `zip`, `python3` が必要です。

```bash
bash build.sh          # → build/DataMeter.apk
```

- `ANDROID_HOME` が設定され `platforms/android-34` があれば Android SDK を使用
- 無ければ `tools/fetch-tools.sh` が npm レジストリからビルドツールを `.tools/` に取得

署名鍵は `release.jks`（リポジトリには含めない）。無い場合は自動生成されますが、別署名になるため既存アプリへの上書きインストールはできません。
環境変数 `KEYSTORE` / `KS_PASS` / `KEY_ALIAS` で変更できます。

### GitHub Actions

`main` への push で APK をビルドし、Actions の Artifacts に `DataMeter-apk` として保存します。
手元と同じ鍵で署名するには、リポジトリの Settings → Secrets and variables → Actions に以下を登録：

- `KEYSTORE_BASE64` … `base64 -w0 release.jks` の出力（Windows: `certutil -encode` など）
- `KS_PASS` … キーストアのパスワード
- `KEY_ALIAS` … 鍵エイリアス

## 構成

```
AndroidManifest.xml
src/jp/local/datameter/
  MainActivity.java   メイン画面（UIはコードで構築）
  UsageWidget.java    ホーム画面ウィジェット
  DataUsage.java      通信量取得・期間計算・書式
  BarChart.java       日別棒グラフ
res/                  ウィジェットレイアウト・アイコン等
build.sh              ビルドスクリプト
zipalign.py           簡易 zipalign
tools/fetch-tools.sh  SDK無し環境用ツール取得
```

## 注意

- 日別・今日の値は OS の記録単位（最大2時間程度）に丸められることがあります。
- キャリアの課金カウントとは計測方法の違いで多少差が出る場合があります。
