# IP Cam Streamer (ipwcam)

[日本語版はこちら](./README.ja.md)

A demo Flask application that works in conjunction with the **IP Cam Streamer APK** for Android, allowing you to view the camera footage from an Android device on the same network in real time via a PC browser.

Simply scan the QR code displayed on screen with an Android device to start streaming.

---

## File Structure

```
ipwcam/
├── app.py              # Flask server main application
├── setup.sh            # Creates virtual environment and installs dependencies
├── start.sh            # Application startup script
├── ipwcam.apk          # IP Cam Streamer APK for Android (prototype)
├── templates/
│   └── index.html      # Main page (QR code display & video playback)
├── static/
│   └── styles.css      # Stylesheet
├── media/              # Recording & capture save directory (not tracked by git)
├── venv/               # Python virtual environment (not tracked by git)
└── .gitignore
```

---

## How It Works

1. Start the Flask server on the PC — a **QR code** containing the server's IP address is displayed in the browser.
2. Install and launch the **IP Cam Streamer APK** on the Android device, then scan the displayed QR code.
3. The APK POST-streams MJPEG to the QR code's URL (`http://<server IP>:5500/video`).
4. The Flask server receives the frames and streams them to the browser, playing the video in real time.

---

## Setup

### Prerequisites

- Python 3.8 or later
- PC and Android device connected to the **same Wi-Fi network**

### 1. Install the APK on the Android Device

Transfer `ipwcam.apk` to the Android device and install it.
(You may need to enable "Install from unknown sources" in device settings.)

### 2. Set Up the Server

```bash
./setup.sh
```

This creates a virtual environment (`venv`) and installs the following packages:

- Flask
- opencv-python
- qrcode
- Pillow

### 3. Start the Server

```bash
./start.sh
```

After starting, open the following URL in a browser:

```
http://localhost:5500/video
```

### 4. Connect from the Android Device

1. Launch the IP Cam Streamer app.
2. Scan the QR code displayed in the browser.
3. The app will automatically start streaming and the video will appear in the browser.

---

## Key Features

| Feature | Description |
|------|------|
| QR Code Display | Automatically generates a connection QR code containing the server IP |
| MJPEG Reception | Receives the video stream from Android in real time |
| Browser Streaming | Re-streams received video to the browser |
| Still Capture | Saves a single frame from the stream as JPEG |
| Video Recording | Records the stream as an AVI file; can be stopped on demand |

---

## API Endpoints

| Method | Path | Description |
|----------|------|------|
| GET | `/video` | Main page (QR code & video display) |
| POST | `/video` | Receives MJPEG stream from Android |
| GET | `/video_feed` | MJPEG stream delivery to browser |
| POST | `/set_ip` | Manually set the client IP |
| POST | `/start_recording` | Start recording |
| POST | `/stop_recording` | Stop recording |
| POST | `/capture_frame` | Capture a still image |
