# IP Cam Streamer (Android Client)

Android デバイスのカメラ映像をホスト PC へリアルタイム配信する MJPEG ストリーミングアプリです。
ホスト PC の URL を QR コードで読み取るか手入力することで、すぐに配信を開始できます。

---

## 概要

| 項目 | 内容 |
|---|---|
| プラットフォーム | Android 8.0 (API 26) 以上 |
| 配信プロトコル | MJPEG (multipart/x-mixed-replace) over HTTP POST |
| 接続方法 | QR コードスキャン または URL 手入力 |
| 使用カメラ | 背面カメラ |
| 映像品質 | JPEG 圧縮率 70% |

---

## 機能

- **QR コードスキャン** — ML Kit でリアルタイムに QR コードを認識し、URL を自動入力
- **URL 手入力** — `http://` または `https://` から始まる任意の URL を直接指定して接続
- **MJPEG 配信** — OkHttp を使った HTTP POST ストリームで連続フレームを送信
- **FPS / フレーム数モニタリング** — 配信中の状態をリアルタイム表示
- **向き自動補正** — デバイスの回転に合わせて映像を自動回転 (0 / 90 / 180 / 270°)
- **ダークテーマ UI** — Material Design 3 ベースのダークテーマ

---

## ファイル構造

```
client/
├── app/
│   ├── build.gradle.kts                  # モジュールビルド設定
│   └── src/
│       └── main/
│           ├── AndroidManifest.xml       # パーミッション・Activity 定義
│           ├── java/com/example/myapplication/
│           │   ├── MainActivity.kt       # トップ画面（QR / 手入力）
│           │   ├── QRScanActivity.kt     # QR コードスキャン画面
│           │   └── StreamingActivity.kt  # 映像配信画面
│           └── res/
│               ├── layout/
│               │   ├── activity_main.xml
│               │   ├── activity_qr_scan.xml
│               │   └── activity_streaming.xml
│               ├── drawable/
│               │   └── qr_scan_frame.xml # QR スキャン枠 (赤コーナー)
│               └── values/
│                   ├── colors.xml
│                   ├── strings.xml
│                   └── themes.xml
├── gradle/
│   └── libs.versions.toml               # 依存ライブラリのバージョン一覧
├── build.gradle.kts                     # ルートビルド設定
└── settings.gradle.kts
```

---

## 画面遷移

```
MainActivity（トップ画面）
├── [QR コードを読み込んで配信開始] ──→ QRScanActivity
│                                           └── QR 検出成功 ──→ StreamingActivity
└── [接続して配信開始] ─────────────────────────────────────→ StreamingActivity
```

### MainActivity

- アプリのエントリポイント
- QR スキャンボタンと URL 手入力フィールドを提供
- カメラパーミッションをここで確認・要求

### QRScanActivity

- CameraX + ML Kit でリアルタイム QR コード認識
- `http://` または `https://` で始まる URL のみ有効とする
- 認識後は URL を `Intent` で `MainActivity` に返し、即座に配信へ遷移

### StreamingActivity

- CameraX で背面カメラをキャプチャ
- 各フレームを YUV → JPEG に変換しキューへ投入
- 専用スレッドがキューからフレームを取り出し OkHttp の HTTP POST ストリームで送信
- FPS・フレーム数・エラーをリアルタイム表示

---

## アーキテクチャ / 技術スタック

### 映像配信パイプライン

```
[CameraX ImageAnalysis]
        │ YUV_420_888
        ▼
[JPEG 変換 + 向き補正]  ← OrientationEventListener
        │
        ▼
[LinkedBlockingQueue (最大 3 フレーム)]
        │
        ▼
[OkHttp RequestBody.writeTo()]
        │ HTTP POST
        ▼
[ホスト PC の受信サーバー]
```

### MJPEG フォーマット

```
--ipcamstreamer_boundary\r\n
Content-Type: image/jpeg\r\n
Content-Length: <サイズ>\r\n
\r\n
<JPEG バイナリ>\r\n
--ipcamstreamer_boundary\r\n
...
--ipcamstreamer_boundary--\r\n  ← 終了時
```

---

## 主要ライブラリ

| ライブラリ | バージョン | 用途 |
|---|---|---|
| androidx.camera:camera-camera2 | 1.3.4 | カメラ映像取得 |
| androidx.camera:camera-lifecycle | 1.3.4 | CameraX ライフサイクル管理 |
| androidx.camera:camera-view | 1.3.4 | PreviewView コンポーネント |
| com.google.mlkit:barcode-scanning | 17.3.0 | QR コード認識 |
| com.squareup.okhttp3:okhttp | 4.12.0 | HTTP ストリーミング送信 |
| com.google.android.material:material | 1.12.0 | Material Design 3 UI |
| androidx.constraintlayout:constraintlayout | 2.1.4 | レイアウト |

---

## ビルド設定

| 項目 | 値 |
|---|---|
| namespace | `com.example.myapplication` |
| compileSdk | 35 (Android 15) |
| minSdk | 26 (Android 8.0) |
| targetSdk | 35 (Android 15) |
| Kotlin | 2.0.21 |
| AGP | 9.1.0 |
| Java 互換性 | VERSION_11 |

---

## パーミッション

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-feature android:name="android.hardware.camera" android:required="true" />
```

| パーミッション | 用途 | 実行時要求 |
|---|---|---|
| `CAMERA` | QR スキャン・映像キャプチャ | 必要 |
| `INTERNET` | HTTP POST 送信 | 不要（自動付与） |

---

## セットアップ・ビルド手順

### 前提条件

- Android Studio Ladybug 以降
- Android SDK 35

### ビルド

```bash
cd client
./gradlew assembleDebug
```

生成 APK: `app/build/outputs/apk/debug/app-debug.apk`

### インストール

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## 使い方

### ホスト PC の準備

ホスト PC 側で MJPEG の HTTP POST を受け付けるサーバーを起動し、サーバーの URL（例: `http://192.168.1.10:5500/video`）を確認しておきます。

### Android アプリの操作

#### QR コードで接続する場合

1. ホスト PC 側で接続先 URL を QR コードとして表示する
2. アプリを起動し **「QR コードを読み込んで配信開始」** をタップ
3. QR コードをカメラでスキャン → 自動的に配信開始

#### URL を手入力する場合

1. アプリを起動し、入力フィールドに URL を入力
   例: `http://192.168.1.10:5500/video`
2. **「接続して配信開始」** をタップ → 配信開始

### 配信中の画面

- 上半分: ライブカメラプレビュー（LIVE バッジ表示）
- 下半分: 配信先 URL / FPS / 送信フレーム数 / エラーメッセージ
- **「配信を停止する」** ボタンで配信終了

---

## 注意事項

- 本アプリは HTTP（平文）通信を許可しています (`usesCleartextTraffic="true"`)。セキュリティが求められる環境では HTTPS サーバーへ接続してください。
- 映像ストリームに認証機能はありません。信頼できるネットワーク内でのみ使用してください。
- Android 8.0 (API 26) 未満の端末には非対応です。
