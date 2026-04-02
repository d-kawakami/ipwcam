# IP Cam Streamer (Android Client)

[日本語版はこちら](./README.ja.md)

An MJPEG streaming app that broadcasts Android device camera footage to a host PC in real time.
Simply scan the host PC's URL via QR code or enter it manually to start streaming immediately.
The app also features a "Host Streaming" mode where the Android device itself acts as an HTTP server and streams its camera to browsers on other devices via QR code.

---

## Overview

| Item | Details |
|---|---|
| Platform | Android 8.0 (API 26) or later |
| Streaming Protocol | MJPEG (multipart/x-mixed-replace) over HTTP |
| Connection Method | QR code scan or manual URL entry |
| Camera Used | Rear camera |
| Video Quality | JPEG compression at 70% |

---

## Features

### Client Streaming Mode
- **QR Code Scan** — Real-time QR code recognition via ML Kit to auto-fill the host PC's URL
- **Manual URL Entry** — Directly specify any URL starting with `http://` or `https://`
- **MJPEG Streaming** — Sends continuous frames to the host PC via HTTP POST stream using OkHttp
- **FPS / Frame Count Monitoring** — Real-time display of streaming status
- **Auto Orientation Correction** — Automatically rotates video to match device orientation (0 / 90 / 180 / 270°)

### Host Streaming Mode (New Feature)
- **Auto IP Detection & QR Display** — Automatically retrieves the WiFi / LAN IPv4 address and displays the stream URL (`http://<IP>:8080/stream`) as a QR code
- **Built-in HTTP Server** — Serves MJPEG stream on port 8080; other devices can view it simply by scanning the QR code in their browser
- **Multiple Client Simultaneous Streaming** — Supports simultaneous streaming to multiple devices (live connection count display)
- **Resolution & Frame Rate Settings** — Switch between FHD / HD / VGA and 30 / 24 / 20 / 15 / 10 fps via dialog
- **Stream Preview** — View a scaled-down preview of the outgoing video on the same device
- **Dark Theme UI** — Dark theme based on Material Design 3

---

## File Structure

```
client/
├── app/
│   ├── build.gradle.kts                  # Module build configuration
│   └── src/
│       └── main/
│           ├── AndroidManifest.xml       # Permissions & Activity definitions
│           ├── java/com/example/myapplication/
│           │   ├── MainActivity.kt       # Top screen (QR scan / manual entry / host streaming)
│           │   ├── QRScanActivity.kt     # QR code scan screen
│           │   ├── StreamingActivity.kt  # Client streaming screen (send to host PC)
│           │   └── HostStreamActivity.kt # Host streaming screen (stream from device to others)
│           └── res/
│               ├── layout/
│               │   ├── activity_main.xml
│               │   ├── activity_qr_scan.xml
│               │   ├── activity_streaming.xml
│               │   └── activity_host_stream.xml
│               ├── drawable/
│               │   └── qr_scan_frame.xml # QR scan frame (red corners)
│               └── values/
│                   ├── colors.xml
│                   ├── strings.xml
│                   └── themes.xml
├── gradle/
│   └── libs.versions.toml               # Dependency library version catalog
├── build.gradle.kts                     # Root build configuration
└── settings.gradle.kts
```

---

## Screen Flow

```
MainActivity (Top Screen)
├── [Scan QR code and start streaming] ──→ QRScanActivity
│                                              └── QR detected ──→ StreamingActivity (send to host PC)
├── [Connect and start streaming] ──────────────────────────────→ StreamingActivity (send to host PC)
└── [Use this device as host] ──────────────────────────────────→ HostStreamActivity (stream to others)
```

### MainActivity

- App entry point
- Provides QR scan button, URL input field, and host streaming button
- Handles camera permission check and request

### QRScanActivity

- Real-time QR code recognition using CameraX + ML Kit
- Only accepts URLs starting with `http://` or `https://`
- Returns the detected URL to `MainActivity` via `Intent` and transitions to streaming immediately

### StreamingActivity (Client Streaming)

- Captures rear camera using CameraX
- Converts each frame from YUV → JPEG and enqueues it
- A dedicated thread dequeues frames and sends them to the host PC via HTTP POST stream using OkHttp
- Displays FPS, frame count, and errors in real time

### HostStreamActivity (Host Streaming)

- Retrieves the WiFi / LAN IPv4 address and displays `http://<IP>:8080/stream` as a QR code
- Listens on TCP port 8080 and responds to HTTP GET requests with MJPEG responses
- Manages simultaneous streaming to multiple clients using a `CopyOnWriteArrayList`-backed queue
- Allows dynamic resolution and frame rate changes via dialog (rebinds camera after change)
- Updates a scaled-down stream preview every 500 ms

---

## Architecture / Tech Stack

### Client Streaming Pipeline (StreamingActivity)

```
[CameraX ImageAnalysis]
        │ YUV_420_888
        ▼
[JPEG Conversion + Orientation Correction]  ← OrientationEventListener
        │
        ▼
[LinkedBlockingQueue (max 3 frames)]
        │
        ▼
[OkHttp RequestBody.writeTo()]
        │ HTTP POST (MJPEG)
        ▼
[Host PC Receive Server]
```

### Host Streaming Pipeline (HostStreamActivity)

```
[CameraX ImageAnalysis]
        │ YUV_420_888
        ▼
[Bitmap Conversion + Orientation Correction]  ← OrientationEventListener
        │
        ├──→ [Scaled Bitmap] ──→ [Stream Preview (ImageView)]
        │
        ▼
[JPEG Conversion]
        │
        ▼
[Per-client LinkedBlockingQueue (max 5 frames/client)]
        │
        ▼
[TCP ServerSocket (:8080) → Per-client threads]
        │ HTTP/1.1 200 OK (MJPEG)
        ▼
[Browser on other device]
```

### MJPEG Format

```
--<boundary>\r\n
Content-Type: image/jpeg\r\n
Content-Length: <size>\r\n
\r\n
<JPEG binary>\r\n
--<boundary>\r\n
...
```

---

## Key Libraries

| Library | Version | Purpose |
|---|---|---|
| androidx.camera:camera-camera2 | 1.3.4 | Camera capture |
| androidx.camera:camera-lifecycle | 1.3.4 | CameraX lifecycle management |
| androidx.camera:camera-view | 1.3.4 | PreviewView component |
| com.google.mlkit:barcode-scanning | 17.3.0 | QR code recognition (scanning) |
| com.google.zxing:core | 3.5.3 | QR code generation (host streaming screen) |
| com.squareup.okhttp3:okhttp | 4.12.0 | HTTP streaming transmission |
| com.google.android.material:material | 1.12.0 | Material Design 3 UI |
| androidx.constraintlayout:constraintlayout | 2.1.4 | Layout |

---

## Build Configuration

| Item | Value |
|---|---|
| namespace | `com.example.myapplication` |
| compileSdk | 35 (Android 15) |
| minSdk | 26 (Android 8.0) |
| targetSdk | 35 (Android 15) |
| Kotlin | 2.0.21 |
| AGP | 9.1.0 |
| Java Compatibility | VERSION_11 |

---

## Permissions

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-feature android:name="android.hardware.camera" android:required="true" />
```

| Permission | Purpose | Runtime Request |
|---|---|---|
| `CAMERA` | QR scan & video capture | Required |
| `INTERNET` | HTTP send/receive (client & host streaming) | Not required (auto-granted) |
| `ACCESS_NETWORK_STATE` | Network info retrieval (host streaming) | Not required (auto-granted) |

---

## Setup & Build

### Prerequisites

- Android Studio Ladybug or later
- Android SDK 35

### Build

```bash
cd client
./gradlew assembleDebug
```

Output APK: `app/build/outputs/apk/debug/ipwcam-1.0.apk`

### Install

```bash
adb install app/build/outputs/apk/debug/ipwcam-1.0.apk
```

---

## Usage

### Mode 1: Client Streaming (Android → Host PC)

Start a server on the host PC that accepts MJPEG HTTP POST, and note the server URL (e.g., `http://192.168.1.10:5500/video`).

#### Connecting via QR Code

1. Display the destination URL as a QR code on the host PC side
2. Launch the app and tap **"Scan QR code and start streaming"**
3. Scan the QR code with the camera → streaming starts automatically

#### Connecting via Manual URL Entry

1. Launch the app and enter the URL in the input field
   e.g., `http://192.168.1.10:5500/video`
2. Tap **"Connect and start streaming"** → streaming begins

#### While Streaming

- Top half: Live camera preview (LIVE badge displayed)
- Bottom half: Destination URL / FPS / Frames sent / Error messages
- Tap **"Stop Streaming"** to end the stream

---

### Mode 2: Host Streaming (Android → Browser on Other Device)

1. Connect the Android device to WiFi or LAN
2. Launch the app and tap **"Use this device as host"**
3. Scan the displayed QR code with another device (PC, phone, tablet, etc.)
4. The other device's browser automatically opens the stream URL (`http://<IP>:8080/stream`) and plays the video

#### Host Streaming Screen

- **QR Code**: Displays the stream URL (scan from another device to view)
- **Stream Preview**: Scaled-down view of the outgoing video (updated every 500 ms)
- **Streaming Info**: FPS / Frames sent / Connected clients
- **"Settings" button**: Change resolution (FHD / HD / VGA) and frame rate (30 / 24 / 20 / 15 / 10 fps)
- **"Stop Streaming" button**: Ends the stream

---

## Notes

- This app allows cleartext HTTP communication (`usesCleartextTraffic="true"`). Use an HTTPS server in security-sensitive environments.
- Host streaming mode uses port 8080. Connections will fail if this port is blocked by a firewall.
- The video stream has no authentication. Use only on trusted networks.
- Not supported on Android devices below Android 8.0 (API 26).
