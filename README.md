# IP Cam Streamer (ipwcam)

Android 向けに試作した **IP Cam Streamer APK** と連携し、同一ネットワーク上の Android 端末のカメラ映像を PC のブラウザでリアルタイム視聴するデモ用 Flask アプリです。

画面に表示された QR コードを Android 端末で読み取るだけでストリーミングが開始されます。

---

## ファイル構造

```
ipwcam/
├── app.py              # Flask サーバー本体
├── setup.sh            # 仮想環境の作成・依存パッケージのインストール
├── start.sh            # アプリ起動スクリプト
├── ipwcam.apk       # Android 向け IP Cam Streamer APK（試作版）
├── templates/
│   └── index.html      # メイン画面（QR コード表示・映像再生）
├── static/
│   └── styles.css      # スタイルシート
├── media/              # 録画・キャプチャ保存先（git 管理外）
├── venv/               # Python 仮想環境（git 管理外）
└── .gitignore
```

---

## 動作の仕組み

1. PC 側で Flask サーバーを起動すると、サーバーの IP アドレスを埋め込んだ **QR コード**がブラウザ上に表示されます。
2. Android 端末で **IP Cam Streamer APK** をインストール・起動し、表示された QR コードを読み取ります。
3. APK が QR コードの URL（`http://<サーバーIP>:5500/video`）へ MJPEG ストリームを POST 送信します。
4. Flask サーバーが受信したフレームをブラウザへ配信し、映像がリアルタイムで再生されます。

---

## セットアップ手順

### 前提条件

- Python 3.8 以上
- PC と Android 端末が **同一 Wi-Fi ネットワーク**に接続されていること

### 1. APK を Android 端末にインストール

`ipwcam.apk` を Android 端末に転送してインストールしてください。
（提供元不明アプリのインストールを許可する設定が必要な場合があります）

### 2. サーバーのセットアップ

```bash
./setup.sh
```

仮想環境 (`venv`) の作成と以下パッケージのインストールが行われます：

- Flask
- opencv-python
- qrcode
- Pillow

### 3. サーバーの起動

```bash
./start.sh
```

起動後、ブラウザで以下の URL を開きます：

```
http://localhost:5500/video
```

### 4. Android 端末で接続

1. IP Cam Streamer アプリを起動します。
2. ブラウザ画面に表示された QR コードをスキャンします。
3. アプリが自動的にストリーミングを開始し、ブラウザ上に映像が表示されます。

---

## 主な機能

| 機能 | 説明 |
|------|------|
| QR コード表示 | サーバー IP を含む接続用 QR コードを自動生成 |
| MJPEG 受信 | Android からの映像ストリームをリアルタイム受信 |
| ブラウザ配信 | 受信映像をブラウザへ再配信 |
| 静止画キャプチャ | ストリームから 1 フレームを JPEG 保存 |
| 動画録画 | ストリームを AVI ファイルとして録画・停止 |

---

## API エンドポイント

| メソッド | パス | 説明 |
|----------|------|------|
| GET | `/video` | メイン画面（QR コード・映像表示） |
| POST | `/video` | Android からの MJPEG ストリーム受信 |
| GET | `/video_feed` | ブラウザへの MJPEG 配信 |
| POST | `/set_ip` | クライアント IP の手動設定 |
| POST | `/start_recording` | 録画開始 |
| POST | `/stop_recording` | 録画停止 |
| POST | `/capture_frame` | 静止画キャプチャ |
