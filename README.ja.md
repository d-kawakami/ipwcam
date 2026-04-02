# ipwcam

[English version here](./README.md)

ローカルエリアネットワーク（LAN）上でAndroidデバイスのカメラ映像をPCのブラウザにリアルタイム配信するシステムの試作品です。
<br>
<img src=https://github.com/user-attachments/assets/c92ec4db-c840-445a-ab14-e10f2ce50688 width="300">

## システム概要

```
[Androidデバイス] --MJPEG over HTTP POST--> [Flaskサーバ] --MJPEG over HTTP GET--> [ブラウザ]
```

AndroidアプリがカメラフレームをMJPEG形式でFlaskサーバへPUSH送信し、サーバはブラウザ向けにそのまま配信します。接続先URLはQRコードまたは手動入力で指定します。

## リポジトリ構成

```
ipwcam/
├── client/   # Androidクライアントアプリ (Kotlin)
└── server/   # 受信・配信サーバ (Python / Flask)
```

各フォルダに詳細なREADMEがあります。

- [クライアント詳細](./client/README.md)
- [サーバ詳細](./server/README.md)

## 技術スタック

| コンポーネント | 言語・フレームワーク |
|---|---|
| Androidクライアント | Kotlin, CameraX, OkHttp, ML Kit |
| サーバ | Python, Flask, OpenCV |
| フロントエンド | HTML / JavaScript |
| ストリーミング形式 | MJPEG (multipart/x-mixed-replace) |

## クイックスタート

### 1. サーバを起動する

```bash
cd server
./setup.sh   # 初回のみ
./start.sh   # Flask サーバ起動 (port 5500)
```

### 2. Androidアプリをビルドする

Android Studio でクライアントプロジェクトを開きビルドします。

```bash
cd client
./gradlew assembleDebug
```

### 3. 接続する

1. ブラウザで `http://<サーバのIPアドレス>:5500` を開く
2. 表示されたQRコードをAndroidアプリでスキャン、またはURLを手動入力
3. アプリの「配信開始」ボタンで映像が流れ始める

## 動作要件

| 対象 | 要件 |
|---|---|
| Androidデバイス | Android 8.0以上 (API 26+) |
| サーバOS | Linux / macOS (bash環境) |
| Pythonバージョン | Python 3.x |
| ネットワーク | クライアントとサーバが同一LAN内にいること |

## ライセンス

MIT License — 詳細は [LICENSE](./LICENSE) を参照してください。
