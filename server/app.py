# -*- coding: utf-8 -*-
from flask import Flask, render_template, request, jsonify, send_from_directory, flash, redirect, url_for, Response
from jinja2 import ChoiceLoader, FileSystemLoader
import os, io
import socket
from PIL import Image, ImageOps
import cv2
import numpy as np
import sys
import time
import threading
import base64
import qrcode
import ipaddress
import re
import shutil
import subprocess


app = Flask(
    __name__,
    template_folder='templates'  # templates フォルダが app.py と同じ階層にある場合
)
app.secret_key = os.urandom(24)  # Generate a unique secret key
# テンプレート検索パスを複数設定
app.jinja_loader = ChoiceLoader([
    FileSystemLoader(os.path.join(os.path.dirname(__file__), 'templates')),
    FileSystemLoader(os.path.abspath(os.path.join(os.path.dirname(__file__), '..', 'common_templates')))
])

# グローバル変数でクライアントのIPアドレスを保持
ip_address = None
client_ip = None
server_ip = None
is_recording = False
record_thread = None
record_save_path = None

# MJPEG PUSH受信用
latest_frame = None
frame_lock = threading.Condition()
push_active = False
frame_seq = 0              # 受信フレームの連番(新着判定用)
frame_ts = 0.0             # 最終フレーム受信時刻(サーバー時刻, epoch秒)
capture_interval_ms = 0    # クライアントが申告した送信間隔(0=リアルタイム)

# 画像を保存するディレクトリ
MEDIA_FOLDER = '../capture'

app.config['MEDIA_FOLDER'] = MEDIA_FOLDER

if not os.path.exists(MEDIA_FOLDER):
    os.makedirs(MEDIA_FOLDER)

TAILSCALE_NET = ipaddress.ip_network('100.64.0.0/10')

def _is_tailscale_ip(ip):
    try:
        return ipaddress.ip_address(ip) in TAILSCALE_NET
    except ValueError:
        return False

def get_tailscale_ipv4():
    """このサーバーのTailscale IPv4(100.64.0.0/10)を返す。無ければ None。
    環境変数 IPWCAM_TAILSCALE_IP で手動指定も可能。"""
    env_ip = os.environ.get('IPWCAM_TAILSCALE_IP')
    if env_ip and _is_tailscale_ip(env_ip):
        return env_ip
    # 1) Tailscaleの仮想アドレス宛の経路から自分側アドレスを得る(外部通信は発生しない)
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(('100.100.100.100', 80))
        ip = s.getsockname()[0]
        if _is_tailscale_ip(ip):
            return ip
    except Exception:
        pass
    finally:
        s.close()
    # 2) tailscale CLI
    for exe in (shutil.which('tailscale'), '/Applications/Tailscale.app/Contents/MacOS/Tailscale'):
        if not exe or not os.path.exists(exe):
            continue
        try:
            out = subprocess.run([exe, 'ip', '-4'], capture_output=True, text=True, timeout=3).stdout.split()
            for ip in out:
                if _is_tailscale_ip(ip):
                    return ip
        except Exception:
            pass
    return None

def make_qr_b64(url):
    qr = qrcode.make(url)
    buffer = io.BytesIO()
    qr.save(buffer, format="PNG")
    return base64.b64encode(buffer.getvalue()).decode('utf-8')

def get_server_ipv4():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        ip_address = s.getsockname()[0]
    except Exception as e:
        ip_address = '127.0.0.1'
    finally:
        s.close()
    return ip_address

def get_client_ip():
    x_forwarded_for = request.environ.get('HTTP_X_FORWARDED_FOR')
    if x_forwarded_for:
        ip = x_forwarded_for.split(',')[0]
    else:
        ip = request.environ.get('REMOTE_ADDR')
    return ip

def get_displayed_ip():
    client_ip = get_client_ip()
    server_ip = get_server_ipv4()

    if client_ip == '127.0.0.1':
        return 'localhost'
    else:
        return server_ip

def correct_orientation(jpeg_bytes):
    """EXIFの向き情報をもとにJPEGを自動補正して返す"""
    try:
        img = Image.open(io.BytesIO(jpeg_bytes))
        img = ImageOps.exif_transpose(img)
        buf = io.BytesIO()
        img.save(buf, format='JPEG', quality=85)
        return buf.getvalue()
    except Exception:
        return jpeg_bytes

@app.route('/video', methods=['GET', 'POST'])
def video_endpoint():
    if request.method == 'POST':
        return receive_mjpeg_push()
    return index()

def _commit_frame(body):
    """受信した1フレームを最新フレームとして保存し、待機中の配信スレッドへ通知"""
    global latest_frame, frame_seq, frame_ts
    if not body:
        return
    with frame_lock:
        latest_frame = body
        frame_seq += 1
        frame_ts = time.time()
        frame_lock.notify_all()

def receive_mjpeg_push():
    """AndroidからのMJPEG PUSHを受信してlatest_frameに保存"""
    global latest_frame, push_active, frame_seq, frame_ts, capture_interval_ms

    content_type = request.content_type or ''
    boundary = None
    for part in content_type.split(';'):
        part = part.strip()
        if part.startswith('boundary='):
            boundary = part[9:].strip()
            break

    if not boundary:
        return "Bad Request: no boundary", 400

    push_active = True
    try:
        capture_interval_ms = max(0, int(request.headers.get('X-Capture-Interval-Ms', '0')))
    except ValueError:
        capture_interval_ms = 0
    sep = ('--' + boundary).encode()
    buf = b''

    try:
        for chunk in request.stream:
            buf += chunk
            while True:
                start = buf.find(sep)
                if start == -1:
                    break
                hdr_start = start + len(sep)
                if len(buf) < hdr_start + 2:
                    break
                if buf[hdr_start:hdr_start + 2] == b'--':
                    # 終端バウンダリ
                    buf = b''
                    break
                header_end = buf.find(b'\r\n\r\n', hdr_start)
                if header_end == -1:
                    break
                headers = buf[hdr_start:header_end].decode('latin-1').lower()
                body_start = header_end + 4
                m = re.search(r'content-length:\s*(\d+)', headers)
                if m:
                    # Content-Length があれば次のバウンダリを待たずに即確定する
                    # (送信間隔が長い場合でも最新の1枚をすぐ表示できる)
                    n = int(m.group(1))
                    if len(buf) < body_start + n:
                        break
                    _commit_frame(buf[body_start:body_start + n])
                    buf = buf[body_start + n:]
                else:
                    # Content-Length が無い送信側向け: 次のバウンダリで確定
                    nxt = buf.find(sep, body_start)
                    if nxt == -1:
                        break
                    _commit_frame(buf[body_start:nxt].rstrip(b'\r\n'))
                    buf = buf[nxt:]
    except OSError:
        # Client disconnected mid-stream (e.g. malformed final chunk)
        pass
    finally:
        push_active = False

    return "OK", 200

def index():
    global server_ip
    global ip_address
    server_ip = get_server_ipv4() # client_ipとserver_ipを取得
    ip_address = get_displayed_ip()

    """カメラ映像を表示するHTMLページとクライアントのIPアドレス表示"""
    # クライアントのIPアドレスを取得
    global client_ip
    video_feed = None
    audio_feed = None
    if client_ip == '127.0.0.1':
        ip_address = 'localhost'

    if client_ip:
        # クライアントのIPアドレスに基づいてvideo_feed, audio_feedのURLを生成
        video_feed = f"http://{client_ip}:8080/video"
        audio_feed = f"http://{client_ip}:8080/audio.wav"
    else:
        video_feed = None
        audio_feed = None

    # QRコード生成(サーバIPを表示)
    qr_code_data = make_qr_b64(f"http://{server_ip}:5500/video")
    tailscale_ip = get_tailscale_ipv4()
    qr_code_data_ts = make_qr_b64(f"http://{tailscale_ip}:5500/video") if tailscale_ip else None


#   client_ip=client_ipとするとclient_ipは常に192.168.10.9に固定される。    
#    return render_template('index.html', ip_address=ip_address, client_ip=client_ip,  video_feed=video_feed, audio_feed=audio_feed)
#    client_ip=request.remote_addr とすれば任意のクライアントIPアドレスで利用可能になる。
#    手順として、クライアント端末側の「IPアドレスを送信」ボタンをおされると、サーバ側のclient_ipに
#    クライアント端末のIPアドレスが保存され、クライアント端末の撮影動画がサーバ側で表示される。
    return render_template('index.html', ip_address=ip_address, server_ip=server_ip, client_ip=request.remote_addr, video_feed=video_feed, audio_feed=audio_feed, qr_code_data=qr_code_data,
                           tailscale_ip=tailscale_ip, qr_code_data_ts=qr_code_data_ts)

def gen(video_url):
    cap = cv2.VideoCapture(video_url)

    # フレームレートとバッファサイズの設定
    cap.set(cv2.CAP_PROP_BUFFERSIZE, 1)  # バッファサイズを最小化
    cap.set(cv2.CAP_PROP_FPS, 30)        # FPS設定

    while True:
        ret, frame = cap.read()
        if not ret:
            break
        # フレームをJPEGにエンコード
        _, jpeg = cv2.imencode('.jpg', frame)
        # フレームをバイトに変換して送信
        frame = jpeg.tobytes()
        yield (b'--frame\r\n'
               b'Content-Type: image/jpeg\r\n\r\n' + frame + b'\r\n\r\n')

def gen_async(video_url):
    cap = cv2.VideoCapture(video_url)
    
    # フレームレートとバッファサイズの設定
    cap.set(cv2.CAP_PROP_BUFFERSIZE, 1)  # バッファサイズを最小化
    cap.set(cv2.CAP_PROP_FPS, 30)        # FPS設定
    sys. stdout.flush() # 画面の反応が遅くなるのを防ぐ

    while True:
        ret, frame = cap.read()
        if not ret:
            break
        _, jpeg = cv2.imencode('.jpg', frame)
        frame = jpeg.tobytes()
        yield (b'--frame\r\n'
               b'Content-Type: image/jpeg\r\n\r\n' + frame + b'\r\n\r\n')

    cap.release()

import cv2
import time

@app.route('/set_ip', methods=['POST'])
def set_ip():
    """クライアントのIPアドレスを受け取り、ビデオフィードURLを設定"""
    global client_ip
    # フォームから送信されたIPアドレスを取得
    client_ip = request.form['client_ip']
    print(client_ip)
    sys. stdout.flush() 
    # IP設定後にindexページにリダイレクトして、video_feedを更新
    return redirect(url_for('index', client_ip=client_ip))
    #return jsonify({"client_ip": client_ip})

def gen_from_push():
    """MJPEG PUSHで受信したlatest_frameをブラウザに配信。
    新しいフレームが届いた時だけ送る(間隔配信時の無駄な再送を避ける)。
    15秒間フレームが無ければ同じフレームを再送して接続維持/切断検知を行う。"""
    last_seq = -1
    last_sent = 0.0
    while True:
        with frame_lock:
            if frame_seq == last_seq:
                frame_lock.wait(timeout=1.0)
            frame = latest_frame
            seq = frame_seq
        if not frame:
            continue
        now = time.time()
        if seq != last_seq or now - last_sent >= 15:
            last_seq = seq
            last_sent = now
            yield (b'--frame\r\n'
                   b'Content-Type: image/jpeg\r\n'
                   b'Content-Length: ' + str(len(frame)).encode() + b'\r\n\r\n'
                   + frame + b'\r\n')

@app.route('/latest.jpg')
def latest_jpg():
    """最新の1枚をJPEGで返す(間隔配信の閲覧・外部スクリプトからの取得用)"""
    with frame_lock:
        frame = latest_frame
        seq = frame_seq
        ts = frame_ts
    if not frame:
        return "no frame yet", 404
    resp = Response(frame, mimetype='image/jpeg')
    resp.headers['Cache-Control'] = 'no-store'
    resp.headers['X-Frame-Seq'] = str(seq)
    resp.headers['X-Captured-At'] = f"{ts:.3f}"
    return resp

@app.route('/status')
def status():
    """受信状況(閲覧ページが1秒ごとに参照する)"""
    with frame_lock:
        seq = frame_seq
        ts = frame_ts
        interval = capture_interval_ms
    now = time.time()
    return jsonify({
        "seq": seq,
        "last_frame_at": ts or None,
        "age_sec": (now - ts) if ts else None,
        "interval_ms": interval,
        "push_active": push_active,
        "server_time": now,
    })

@app.route('/video_feed')
def video_feed():
    """映像をストリーミング（フレームが届くまで待機）"""
    return Response(gen_from_push(),
                    mimetype='multipart/x-mixed-replace; boundary=frame')

# PUSHで受信したlatest_frameをAVIファイルとして録画するスレッド関数
def record_video_from_push(save_path):
    global is_recording, latest_frame, frame_lock

    # 最初のフレームからサイズを取得
    for _ in range(50):  # 最大5秒待機
        with frame_lock:
            first = latest_frame
        if first:
            break
        time.sleep(0.1)
    else:
        return

    nparr = np.frombuffer(first, np.uint8)
    img = cv2.imdecode(nparr, cv2.IMREAD_COLOR)
    if img is None:
        return
    h, w = img.shape[:2]

    fourcc = cv2.VideoWriter_fourcc(*'XVID')
    out = cv2.VideoWriter(save_path, fourcc, 20.0, (w, h))

    prev_frame = None
    while is_recording:
        with frame_lock:
            frame_data = latest_frame
        if frame_data and frame_data is not prev_frame:
            nparr = np.frombuffer(frame_data, np.uint8)
            frame = cv2.imdecode(nparr, cv2.IMREAD_COLOR)
            if frame is not None:
                out.write(frame)
            prev_frame = frame_data
        else:
            time.sleep(0.01)

    out.release()

@app.route('/start_recording', methods=['POST'])
def start_recording():
    """Start video recording from the push stream"""
    global is_recording, record_thread, record_save_path
    with frame_lock:
        has_frame = latest_frame is not None
    if has_frame and not is_recording:
        record_save_path = os.path.join(app.config['MEDIA_FOLDER'], f"video_{time.strftime('%Y%m%d_%H%M%S')}.avi")
        is_recording = True
        record_thread = threading.Thread(target=record_video_from_push, args=(record_save_path,), daemon=True)
        record_thread.start()
        return jsonify({"status": "Recording started", "file": record_save_path})
    return jsonify({"status": "Failed to start recording"}), 400

@app.route('/stop_recording', methods=['POST'])
def stop_recording():
    """Stop the video recording"""
    global is_recording, record_save_path
    if is_recording:
        is_recording = False
        if record_thread:
            record_thread.join(timeout=5)
        return jsonify({"status": "Recording stopped", "file": record_save_path})
    return jsonify({"status": "No recording in progress"}), 400

@app.route('/capture_frame', methods=['POST'])
def capture_frame():
    """Capture a single frame from the push stream and save it as JPEG"""
    with frame_lock:
        frame_data = latest_frame
    if frame_data:
        save_path = os.path.join(app.config['MEDIA_FOLDER'], f"frame_{time.strftime('%Y%m%d_%H%M%S')}.jpg")
        with open(save_path, 'wb') as f:
            f.write(frame_data)
        return jsonify({"status": "Frame captured", "file": save_path})
    return jsonify({"status": "Failed to capture frame"}), 400

@app.template_filter('b64encode')
def b64encode_filter(data):
    return base64.b64encode(data).decode('utf-8')

if __name__ == '__main__':
    app.run(host="0.0.0.0", debug=os.environ.get("IPWCAM_DEBUG") == "1", port=5500, threaded=True)
