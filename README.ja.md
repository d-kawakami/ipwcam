# ipwcam

[English version here](./README.md)

ローカルエリアネットワーク（LAN）上でAndroidデバイスのカメラ映像をリアルタイム配信するシステムの試作品です。サーバー不要で端末間直接通信するモードと、FlaskサーバーをPC上で起動してブラウザで視聴するモードの2種類に対応しています。

<img src="doc/images/top_scr.jpg" width="220" alt="アプリ起動画面">

*アプリ起動画面 — モードを選んですぐ使えます*

---

## システム概要

### モード1 — 端末間ダイレクト通信（サーバー不要）

```
[配信側 Android] --MJPEG HTTP（組み込みサーバー）--> [受信側 Android]
```

配信側がストリーミングサーバーとして動作し、QRコードを表示します。受信側がそのQRコードをスキャンすることで、PCやサーバーなしで端末間のライブ映像視聴が可能です。

<img src="doc/images/cli_cli.jpg" width="440" alt="端末間通信の様子">

*左：配信側 — QRコードとカメラプレビューを表示。右：受信側 — ライブ映像をアプリ内で受信・表示。*

---

### モード2 — Flaskサーバー経由（ブラウザ視聴）

```
[Androidクライアント] --MJPEG HTTP POST--> [Flaskサーバー] --MJPEG HTTP GET--> [ブラウザ]
```

AndroidアプリがカメラフレームをPC上のFlaskサーバーへPUSH送信し、サーバーが同一ネットワーク上のブラウザへ再配信します。ブラウザからの録画・キャプチャにも対応しています。

<img src="doc/images/srv_cli.jpg" width="480" alt="サーバー連携の様子">

*左：FlaskサーバーがライブPCブラウザ上に表示。右：配信中のAndroidアプリ。*

---

## リポジトリ構成

```
ipwcam/
├── client/   # Androidクライアントアプリ (Kotlin)
└── server/   # 受信・配信サーバー (Python / Flask)
```

各フォルダに詳細なREADMEがあります。

- [クライアント詳細](./client/README.md)
- [サーバー詳細](./server/README.md)

---

## 技術スタック

| コンポーネント | 言語・フレームワーク |
|---|---|
| Androidクライアント | Kotlin, CameraX, OkHttp, ML Kit |
| サーバー | Python, Flask, OpenCV |
| フロントエンド | HTML / JavaScript |
| ストリーミング形式 | MJPEG (multipart/x-mixed-replace) |

---

## クイックスタート

### モード1 — 端末間ダイレクト通信

両端末にアプリをインストールするだけで使えます。PCやサーバーのセットアップは不要です。

**配信側の操作：**
1. **「QRコードを表示して配信開始」** をタップ
2. QRコードとカメラプレビューが表示される

**受信側の操作：**
1. **「QRコードを読み込んで受信開始」** をタップ
2. 配信側に表示されたQRコードをスキャン
3. アプリ内にライブ映像が表示される

### モード2 — Flaskサーバー経由

#### 1. サーバーを起動する（PC側）

```bash
cd server
./setup.sh   # 初回のみ
./start.sh   # Flaskサーバー起動 (port 5500)
```

#### 2. Androidアプリをビルド・インストールする

Android StudioでClientプロジェクトを開いてビルドするか、以下を実行します：

```bash
cd client
./gradlew assembleDebug
```

#### 3. Androidアプリから接続する

- **「QRコードを読み込んで配信開始」** をタップ → ブラウザに表示されたQRをスキャン
- または **「接続して配信開始」** をタップ → URLを手動入力して接続

ブラウザで `http://<サーバーのIPアドレス>:5500` を開くとライブ映像を視聴できます。

---

## 動作要件

| 対象 | 要件 |
|---|---|
| Androidデバイス | Android 8.0以上 (API 26+) |
| サーバーOS | Linux / macOS (bash環境) |
| Pythonバージョン | Python 3.x |
| ネットワーク | すべての端末が同一LAN内にいること |

---

## ライセンス

MIT License — 詳細は [LICENSE](./LICENSE) を参照してください。
