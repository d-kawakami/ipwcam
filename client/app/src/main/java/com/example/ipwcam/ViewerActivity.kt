package com.example.ipwcam

import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
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

    private fun startViewingThread(streamUrl: String) {
        viewThread = Thread {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(0, TimeUnit.SECONDS)
                    .build()

                val request = Request.Builder()
                    .url(streamUrl)
                    .addHeader("User-Agent", "IPCamViewer/1.0")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        if (isViewing.get()) runOnUiThread { showError("接続失敗: HTTP ${response.code}") }
                        return@use
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
                // 正常停止
            } catch (e: Exception) {
                if (isViewing.get()) runOnUiThread { showError("接続エラー: ${e.message}") }
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
                    tvFps.text = "FPS: %.1f".format(currentFps)
                    tvFrameCount.text = "受信フレーム数: ${frameCount.get()}"
                    mainHandler.postDelayed(this, 500)
                }
            }
        })
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
