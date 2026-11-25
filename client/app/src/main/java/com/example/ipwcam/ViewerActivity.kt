package com.example.ipwcam

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class ViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_STREAM_URL = "stream_url"
    }

    private val isViewing = AtomicBoolean(false)
    private val frameCount = AtomicLong(0)
    private var viewThread: Thread? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var ivStream: ImageView
    private lateinit var tvStreamUrl: TextView
    private lateinit var tvFps: TextView
    private lateinit var tvFrameCount: TextView
    private lateinit var tvLiveBadge: TextView
    private lateinit var tvErrorMsg: TextView

    @Volatile private var lastFrameElapsed = 0L  // 最後にフレームを受信した時刻(SystemClock.elapsedRealtime)
    private var lastFpsTime = System.currentTimeMillis()
    private var fpsFrameCount = 0
    private var currentFps = 0.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_viewer)

        val streamUrl = intent.getStringExtra(EXTRA_STREAM_URL)
        if (streamUrl == null) {
            Toast.makeText(this, "受信先URLが不正です", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        ivStream = findViewById(R.id.ivStream)
        tvStreamUrl = findViewById(R.id.tvStreamUrl)
        tvFps = findViewById(R.id.tvFps)
        tvFrameCount = findViewById(R.id.tvFrameCount)
        tvLiveBadge = findViewById(R.id.tvLiveBadge)
        tvErrorMsg = findViewById(R.id.tvErrorMsg)

        tvStreamUrl.text = "受信元: $streamUrl"
        isViewing.set(true)
        startViewingThread(streamUrl)
        startStatusUpdater()

        findViewById<Button>(R.id.btnStop).setOnClickListener { stopViewing() }
    }

    /**
     * 受信先URLの候補を返す。
     * 配信側のQRは「LANアドレス + ?alt=Tailscaleアドレス」を含むことがあるため、
     * 到達できる方を自動で選べるよう両方を候補にする。
     * この端末自身がTailscaleに接続している場合は、Tailscale側を先に試す。
     */
    private fun candidateUrls(raw: String): List<String> {
        val uri = Uri.parse(raw)
        val alt = uri.getQueryParameter("alt")
        val host = uri.host
        if (alt.isNullOrBlank() || host == null) return listOf(raw)
        val scheme = uri.scheme ?: "http"
        val portPart = if (uri.port != -1) ":${uri.port}" else ""
        val path = uri.path ?: ""
        val primary = "$scheme://$host$portPart$path"
        val altUrl = "$scheme://$alt$portPart$path"
        return if (NetUtil.hasTailscale()) listOf(altUrl, primary) else listOf(primary, altUrl)
    }

    private fun startViewingThread(streamUrl: String) {
        val urls = candidateUrls(streamUrl)
        viewThread = Thread {
            val client = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                // 無通信が長く続いた接続(圏外・NAT切断など)を検知して張り直す。
                // 再接続すると配信側は直ちに最新の1枚を送る。
                .readTimeout(180, TimeUnit.SECONDS)
                .build()
            var idx = 0

            // 停止ボタンが押されるまで再接続を繰り返す(長時間監視用)
            while (isViewing.get()) {
                val url = urls[idx % urls.size]
                var connected = false
                try {
                    val request = Request.Builder()
                        .url(url)
                        .addHeader("User-Agent", "IPCamViewer/1.0")
                        .build()

                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            if (isViewing.get()) runOnUiThread { showError("接続失敗: HTTP ${response.code}(再接続します)") }
                            return@use
                        }
                        connected = true
                        runOnUiThread {
                            tvStreamUrl.text = "受信元: $url"
                            showLive()
                        }

                        val contentType = response.header("Content-Type") ?: ""
                        val boundary = contentType.split(";")
                            .map { it.trim() }
                            .firstOrNull { it.startsWith("boundary=") }
                            ?.removePrefix("boundary=")
                            ?: "frame"

                        val inputStream = response.body?.byteStream() ?: return@use
                        parseMjpeg(inputStream.buffered(65536), "--$boundary")
                    }
                } catch (_: InterruptedException) {
                    break
                } catch (_: SocketTimeoutException) {
                    // 無通信タイムアウト: 静かに再接続
                } catch (e: Exception) {
                    if (isViewing.get()) runOnUiThread { showError("接続エラー: ${e.message}(再接続します)") }
                }

                if (!isViewing.get()) break
                if (!connected) idx++  // 繋がらなければ次の候補(LAN <-> Tailscale)を試す
                try {
                    Thread.sleep(if (connected) 3000L else 1000L)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }.also { it.isDaemon = true }
        viewThread?.start()
    }

    private fun parseMjpeg(inputStream: InputStream, separator: String) {
        fun readLine(): String? {
            val sb = StringBuilder()
            var prev = -1
            while (true) {
                val b = try { inputStream.read() } catch (_: Exception) { return null }
                if (b == -1) return if (sb.isEmpty()) null else sb.toString()
                if (b == '\n'.code && prev == '\r'.code) {
                    if (sb.isNotEmpty()) sb.deleteCharAt(sb.length - 1)
                    return sb.toString()
                }
                sb.append(b.toChar())
                prev = b
            }
        }

        // 最初のバウンダリまでスキップ
        while (isViewing.get()) {
            val line = readLine() ?: return
            if (line.startsWith(separator)) break
        }

        while (isViewing.get()) {
            // ヘッダーを読む
            var contentLength = -1
            while (isViewing.get()) {
                val line = readLine() ?: return
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line.substringAfter(":").trim().toIntOrNull() ?: -1
                }
            }

            if (contentLength <= 0) {
                // Content-Length なし → 次のバウンダリまでスキップ
                while (isViewing.get()) {
                    val line = readLine() ?: return
                    if (line.startsWith(separator)) break
                }
                continue
            }

            // ボディを読む
            val body = ByteArray(contentLength)
            var read = 0
            while (read < contentLength && isViewing.get()) {
                val n = try {
                    inputStream.read(body, read, contentLength - read)
                } catch (_: Exception) { return }
                if (n == -1) return
                read += n
            }

            // JPEG をデコードして表示
            val bitmap = BitmapFactory.decodeByteArray(body, 0, body.size)
            if (bitmap != null) {
                mainHandler.post { ivStream.setImageBitmap(bitmap) }
                lastFrameElapsed = SystemClock.elapsedRealtime()
                frameCount.incrementAndGet()
                fpsFrameCount++
                val now = System.currentTimeMillis()
                if (now - lastFpsTime >= 1000) {
                    currentFps = fpsFrameCount * 1000.0 / (now - lastFpsTime)
                    fpsFrameCount = 0
                    lastFpsTime = now
                }
            }

            // 次のバウンダリまで読み飛ばす
            while (isViewing.get()) {
                val line = readLine() ?: return
                if (line.startsWith(separator)) break
            }
        }
    }

    private fun startStatusUpdater() {
        mainHandler.post(object : Runnable {
            override fun run() {
                if (isViewing.get()) {
                    val ago = if (lastFrameElapsed == 0L) "-"
                        else "${(SystemClock.elapsedRealtime() - lastFrameElapsed) / 1000}秒前"
                    tvFps.text = "FPS: %.2f / 最終更新: %s".format(currentFps, ago)
                    tvFrameCount.text = "受信フレーム数: ${frameCount.get()}"
                    mainHandler.postDelayed(this, 500)
                }
            }
        })
    }

    private fun showLive() {
        tvErrorMsg.visibility = View.GONE
        tvLiveBadge.text = "● LIVE"
        tvLiveBadge.setBackgroundColor(0xCC0077B6.toInt())
    }

    private fun showError(message: String) {
        tvErrorMsg.text = message
        tvErrorMsg.visibility = View.VISIBLE
        tvLiveBadge.text = "● ERROR"
        tvLiveBadge.setBackgroundColor(0xFFFF6B6B.toInt())
    }

    private fun stopViewing() {
        isViewing.set(false)
        viewThread?.interrupt()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        isViewing.set(false)
        mainHandler.removeCallbacksAndMessages(null)
    }
}
