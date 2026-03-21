# IP Cam Streamer (Android Client)

Android デバイスのカメラ映像をホスト PC へリアルタイム配信する MJPEG ストリーミングアプリです。
ホスト PC の URL を QR コードで読み取るか手入力することで、すぐに配信を開始できます。
また、Android デバイス自身が HTTP サーバーとして動作し、自身の映像を QR コード経由で別端末のブラウザに配信する「ホスト配信」モードも搭載しています。

---

## 概要

| 項目 | 内容 |
|---|---|
| プラットフォーム | Android 8.0 (API 26) 以上 |
| 配信プロトコル | MJPEG (multipart/x-mixed-replace) over HTTP |
| 接続方法 | QR コードスキャン または URL 手入力 |
| 使用カメラ | 背面カメラ |
| 映像品質 | JPEG 圧縮率 70% |

---

## 機能

### クライアント配信モード
- **QR コードスキャン** — ML Kit でリアルタイムに QR コードを認識し、ホスト PC の URL を自動入力
- **URL 手入力** — `http://` または `https://` から始まる任意の URL を直接指定して接続
- **MJPEG 配信** — OkHttp を使った HTTP POST ストリームで連続フレームをホスト PC へ送信
- **FPS / フレーム数モニタリング** — 配信中の状態をリアルタイム表示
- **向き自動補正** — デバイスの回転に合わせて映像を自動回転 (0 / 90 / 180 / 270°)

### ホスト配信モード（新機能）
- **自動 IP 取得と QR 表示** — WiFi / LAN の IPv4 アドレスを自動取得し、ストリーム URL（`http://<IP>:8080/stream`）を QR コードで画面に表示
- **内蔵 HTTP サーバー** — ポート 8080 で MJPEG ストリームを提供。別端末がブラウザで QR コードを読み取るだけで視聴可能
- **複数クライアント同時配信** — 複数端末への同時配信をサポート（接続台数リアルタイム表示）
- **解像度・フレームレート設定** — FHD / HD / VGA、30 / 24 / 20 / 15 / 10 fps をダイアログで切り替え可能
- **ストリームプレビュー** — 自端末の配信映像を縮小プレビューで確認
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
│           │   ├── MainActivity.kt       # トップ画面（QR スキャン / 手入力 / ホスト配信）
│           │   ├── QRScanActivity.kt     # QR コードスキャン画面
│           │   ├── StreamingActivity.kt  # クライアント配信画面（ホスト PC へ送信）
│           │   └── HostStreamActivity.kt # ホスト配信画面（自端末から別端末へ配信）
│           └── res/
│               ├── layout/
│               │   ├── activity_main.xml
│               │   ├── activity_qr_scan.xml
│               │   ├── activity_streaming.xml
│               │   └── activity_host_stream.xml
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
│                                           └── QR 検出成功 ──→ StreamingActivity（ホスト PC へ送信）
├── [接続して配信開始] ─────────────────────────────────────→ StreamingActivity（ホスト PC へ送信）
└── [自端末をホストにして配信] ────────────────────────────→ HostStreamActivity（別端末へ配信）
```

### MainActivity

- アプリのエントリポイント
- QR スキャンボタン・URL 手入力フィールド・ホスト配信ボタンを提供
- カメラパーミッションをここで確認・要求

### QRScanActivity

- CameraX + ML Kit でリアルタイム QR コード認識
- `http://` または `https://` で始まる URL のみ有効とする
- 認識後は URL を `Intent` で `MainActivity` に返し、即座に配信へ遷移

### StreamingActivity（クライアント配信）

- CameraX で背面カメラをキャプチャ
- 各フレームを YUV → JPEG に変換しキューへ投入
- 専用スレッドがキューからフレームを取り出し OkHttp の HTTP POST ストリームでホスト PC へ送信
- FPS・フレーム数・エラーをリアルタイム表示

### HostStreamActivity（ホスト配信）

- WiFi / LAN の IPv4 アドレスを取得し、`http://<IP>:8080/stream` を QR コードで表示
- ポート 8080 で TCP ソケットを待ち受け、HTTP GET リクエストに対して MJPEG レスポンスを返す
- 複数クライアントへの同時配信を `CopyOnWriteArrayList` のキューで管理
- 解像度・フレームレートをダイアログで動的に変更可能（変更後にカメラを再バインド）
- 配信映像の縮小プレビューを 500 ms 間隔でリアルタイム更新

---

## アーキテクチャ / 技術スタック

### クライアント配信パイプライン（StreamingActivity）

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
        │ HTTP POST (MJPEG)
        ▼
[ホスト PC の受信サーバー]
```

### ホスト配信パイプライン（HostStreamActivity）

```
[CameraX ImageAnalysis]
        │ YUV_420_888
        ▼
[Bitmap 変換 + 向き補正]  ← OrientationEventListener
        │
        ├──→ [縮小 Bitmap] ──→ [ストリームプレビュー (ImageView)]
        │
        ▼
[JPEG 変換]
        │
        ▼
[各クライアントの LinkedBlockingQueue (最大 5 フレーム/台)]
        │
        ▼
[TCP ServerSocket (:8080) → 各クライアントスレッド]
        │ HTTP/1.1 200 OK (MJPEG)
        ▼
[別端末のブラウザ等]
```

### MJPEG フォーマット

```
--<boundary>\r\n
Content-Type: image/jpeg\r\n
Content-Length: <サイズ>\r\n
\r\n
<JPEG バイナリ>\r\n
--<boundary>\r\n
...
```

---

## 主要ライブラリ

| ライブラリ | バージョン | 用途 |
|---|---|---|
| androidx.camera:camera-camera2 | 1.3.4 | カメラ映像取得 |
| androidx.camera:camera-lifecycle | 1.3.4 | CameraX ライフサイクル管理 |
| androidx.camera:camera-view | 1.3.4 | PreviewView コンポーネント |
| com.google.mlkit:barcode-scanning | 17.3.0 | QR コード認識（スキャン） |
| com.google.zxing:core | 3.5.3 | QR コード生成（ホスト配信画面） |
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
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-feature android:name="android.hardware.camera" android:required="true" />
```

| パーミッション | 用途 | 実行時要求 |
|---|---|---|
| `CAMERA` | QR スキャン・映像キャプチャ | 必要 |
| `INTERNET` | HTTP 送受信（クライアント配信・ホスト配信） | 不要（自動付与） |
| `ACCESS_NETWORK_STATE` | ネットワーク情報取得（ホスト配信） | 不要（自動付与） |

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

生成 APK: `app/build/outputs/apk/debug/ipwcam-1.0.apk`

### インストール

```bash
adb install app/build/outputs/apk/debug/ipwcam-1.0.apk
```

---

## 使い方

### モード 1: クライアント配信（Android → ホスト PC）

ホスト PC 側で MJPEG の HTTP POST を受け付けるサーバーを起動し、サーバーの URL（例: `http://192.168.1.10:5500/video`）を確認しておきます。

#### QR コードで接続する場合

1. ホスト PC 側で接続先 URL を QR コードとして表示する
2. アプリを起動し **「QR コードを読み込んで配信開始」** をタップ
3. QR コードをカメラでスキャン → 自動的に配信開始

#### URL を手入力する場合

1. アプリを起動し、入力フィールドに URL を入力
   例: `http://192.168.1.10:5500/video`
2. **「接続して配信開始」** をタップ → 配信開始

#### 配信中の画面

- 上半分: ライブカメラプレビュー（LIVE バッジ表示）
- 下半分: 配信先 URL / FPS / 送信フレーム数 / エラーメッセージ
- **「配信を停止する」** ボタンで配信終了

---

### モード 2: ホスト配信（Android → 別端末のブラウザ）

1. Android デバイスを WiFi または LAN に接続しておく
2. アプリを起動し **「自端末をホストにして配信」** をタップ
3. 画面に表示された QR コードを別端末（PC・スマホ・タブレット等）のカメラで読み取る
4. 別端末のブラウザが自動的にストリーム URL（`http://<IP>:8080/stream`）を開き、映像が再生される

#### ホスト配信画面

- **QR コード**: ストリーム URL を表示（別端末からスキャンして視聴）
- **ストリームプレビュー**: 配信中の映像を縮小表示（500 ms 更新）
- **配信情報**: FPS / 送信フレーム数 / 接続台数
- **「設定」** ボタン: 解像度（FHD / HD / VGA）とフレームレート（30 / 24 / 20 / 15 / 10 fps）を変更
- **「配信を停止する」** ボタンで配信終了

---

## 注意事項

- 本アプリは HTTP（平文）通信を許可しています (`usesCleartextTraffic="true"`)。セキュリティが求められる環境では HTTPS サーバーへ接続してください。
- ホスト配信モードはポート 8080 を使用します。ファイアウォール等でブロックされている場合は接続できません。
- 映像ストリームに認証機能はありません。信頼できるネットワーク内でのみ使用してください。
- Android 8.0 (API 26) 未満の端末には非対応です。
