# ipwcam

[日本語版はこちら](./README.ja.md)

A prototype system that streams Android device camera footage in real time over a local area network (LAN). Supports two independent modes — direct device-to-device streaming with no server required, and browser-based viewing via a Flask server.

<img src="doc/images/top_scr.jpg" width="220" alt="App main screen">

*App main screen — choose a mode to get started*

---

## System Overview

### Mode 1 — Direct Device-to-Device (no server required)

```
[Sender Android] --MJPEG HTTP (built-in server)--> [Receiver Android]
```

One device acts as a streaming server and displays a QR code. The other device scans the QR code and views the live feed directly in the app — no PC or external server needed.

<img src="doc/images/cli_cli.jpg" width="440" alt="Client-to-client streaming">

*Left: Sender — displays QR code and camera preview. Right: Receiver — shows the incoming live feed.*

---

### Mode 2 — Via Flask Server (browser viewing)

```
[Android Client] --MJPEG HTTP POST--> [Flask Server] --MJPEG HTTP GET--> [Browser]
```

The Android app pushes camera frames to a Flask server running on a PC. The server re-streams the video to any browser on the same network. Useful for monitoring or recording via a browser interface.

<img src="doc/images/srv_cli.jpg" width="480" alt="Server-client streaming">

*Left: Flask server displaying the live feed in a browser. Right: Android app streaming to the server.*

---

## Repository Structure

```
ipwcam/
├── client/   # Android client app (Kotlin)
└── server/   # Receive & stream server (Python / Flask)
```

Each folder contains a detailed README.

- [Client details](./client/README.md)
- [Server details](./server/README.md)

---

## Tech Stack

| Component | Language / Framework |
|---|---|
| Android Client | Kotlin, CameraX, OkHttp, ML Kit |
| Server | Python, Flask, OpenCV |
| Frontend | HTML / JavaScript |
| Streaming Format | MJPEG (multipart/x-mixed-replace) |

---

## Quick Start

### Mode 1 — Direct Device-to-Device

Install the app on both devices. No PC or server setup required.

**Sender device:**
1. Tap **"Show QR code and start streaming"**
2. A QR code and camera preview are displayed

**Receiver device:**
1. Tap **"Scan QR code and start receiving"**
2. Scan the QR code shown on the sender
3. The live feed appears in the app

### Mode 2 — Via Flask Server

#### 1. Start the server (PC)

```bash
cd server
./setup.sh   # First time only
./start.sh   # Start Flask server (port 5500)
```

#### 2. Build and install the Android app

Open the `client` project in Android Studio and build it, or run:

```bash
cd client
./gradlew assembleDebug
```

#### 3. Connect from the Android app

- Tap **"Scan QR code and start streaming"** and scan the QR displayed in the browser
- Or tap **"Connect and start streaming"** after entering the server URL manually

Open `http://<server IP>:5500` in a browser to view the live feed.

---

## Requirements

| Target | Requirement |
|---|---|
| Android Device | Android 8.0 or later (API 26+) |
| Server OS | Linux / macOS (bash environment) |
| Python Version | Python 3.x |
| Network | All devices must be on the same LAN |

---

## License

MIT License — see [LICENSE](./LICENSE) for details.

---

## Interval Streaming / Tailscale / Long-run Operation

### Send interval (e.g. monitoring an indicator at a distance)

Set **Send interval (seconds)** on the app's main screen. `0` = real-time (default, same as before), `1` = one frame per second, `60` = one frame per minute. The value is saved and used by:

- **Flask mode** — the app tells the server its interval (`X-Capture-Interval-Ms` header). When the interval is > 0, the browser page automatically switches from MJPEG streaming to refreshing the latest frame, and shows "last received N seconds ago" with a warning if frames stop arriving.
- **Direct mode** — the sender keeps the latest frame fresh, so a viewer that connects in the middle of an interval sees an image immediately. The interval can also be changed in the sender's settings dialog.

New server endpoints: `GET /latest.jpg` (latest frame as JPEG) and `GET /status` (JSON: sequence, last-frame time, interval).

### Tailscale (4G / different networks)

- **Flask mode**: if the server PC runs Tailscale, the page shows a second QR code for `http://100.x.y.z:5500/video`. A phone on 4G with Tailscale installed can scan it, or you can type the URL manually.
- **Direct mode**: if the sender has both a LAN and a Tailscale address, the QR contains the LAN URL plus `?alt=<Tailscale IP>`. The viewer tries the reachable one automatically (Tailscale first when the viewer itself is on Tailscale). If the sender only has Tailscale (e.g. on 4G), that address is used.
- Set `IPWCAM_TAILSCALE_IP` to override server-side detection.

### Long-run operation

While streaming, the app keeps the screen on, holds a CPU wake lock and a Wi-Fi lock, and (once) asks to be excluded from battery optimization. Tap the camera preview to dim the screen to minimum brightness / restore it. Both the sender and the viewer reconnect automatically if the connection drops.

Notes: keeping a phone permanently on charge can swell the battery — use a charge limit or a timer outlet. The Flask debug mode is now off by default (`IPWCAM_DEBUG=1` to enable).
