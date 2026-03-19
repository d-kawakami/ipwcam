package com.example.myapplication

import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class StreamingActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SERVER_URL = "server_url"
        private const val BOUNDARY = "ipcamstreamer_boundary"
        private const val JPEG_QUALITY = 70
    }

    private lateinit var cameraExecutor: ExecutorService
    private val frameQueue = LinkedBlockingQueue<ByteArray>(3)
    private val isStreaming = AtomicBoolean(false)
    private val frameCount = AtomicLong(0)
    private var streamingThread: Thread? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var imageAnalysis: ImageAnalysis
    private lateinit var orientationEventListener: OrientationEventListener
    @Volatile private var surfaceRotation = Surface.ROTATION_0

    // FPS計算用
    private var lastFpsTime = System.currentTimeMillis()
    private var fpsFrameCount = 0
    private var currentFps = 0.0

    private lateinit var tvServerUrl: TextView
    private lateinit var tvFps: TextView
    private lateinit var tvFrameCount: TextView
    private lateinit var tvErrorMsg: TextView
    private lateinit var tvLiveBadge: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_streaming)

        val serverUrl = intent.getStringExtra(EXTRA_SERVER_URL)
        if (serverUrl == null) {
            Toast.makeText(this, "配信先URLが不正です", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        tvServerUrl = findViewById(R.id.tvServerUrl)
        tvFps = findViewById(R.id.tvFps)
        tvFrameCount = findViewById(R.id.tvFrameCount)
        tvErrorMsg = findViewById(R.id.tvErrorMsg)
        tvLiveBadge = findViewById(R.id.tvLiveBadge)

        tvServerUrl.text = "配信先: $serverUrl"

        cameraExecutor = Executors.newSingleThreadExecutor()
        isStreaming.set(true)

        orientationEventListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                surfaceRotation = when {
                    orientation < 45 || orientation >= 315 -> Surface.ROTATION_0
                    orientation < 135 -> Surface.ROTATION_90
                    orientation < 225 -> Surface.ROTATION_180
                    else -> Surface.ROTATION_270
                }
                // 横は両方ROTATION_90に統一（rotationDegreesを0にするため）
                val targetRot = if (surfaceRotation == Surface.ROTATION_90 || surfaceRotation == Surface.ROTATION_270)
                    Surface.ROTATION_90 else surfaceRotation
                if (::imageAnalysis.isInitialized) {
                    imageAnalysis.targetRotation = targetRot
                }
            }
        }
        orientationEventListener.enable()

        startCamera()
        startStreamingThread(serverUrl)
        startStatusUpdater()

        findViewById<Button>(R.id.btnStop).setOnClickListener {
            stopStreaming()
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(
                    findViewById<PreviewView>(R.id.previewView).surfaceProvider
                )
            }

            imageAnalysis = ImageAnalysis.Builder()
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetRotation(windowManager.defaultDisplay.rotation)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                if (isStreaming.get()) {
                    val jpegBytes = imageProxyToJpeg(imageProxy)
                    // キューが満杯なら古いフレームを破棄して新しいフレームを追加
                    if (frameQueue.size >= 3) {
                        frameQueue.poll()
                    }
                    frameQueue.offer(jpegBytes)

                    // FPS計算
                    fpsFrameCount++
                    val now = System.currentTimeMillis()
                    if (now - lastFpsTime >= 1000) {
                        currentFps = fpsFrameCount * 1000.0 / (now - lastFpsTime)
                        fpsFrameCount = 0
                        lastFpsTime = now
                    }
                }
                imageProxy.close()
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis
                )
            } catch (e: Exception) {
                runOnUiThread {
                    showError("カメラ起動エラー: ${e.message}")
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun imageProxyToJpeg(imageProxy: ImageProxy): ByteArray {
        val bitmap = imageProxy.toBitmap()
        // 横右（ROTATION_90）は180°追加補正
        val extra = if (surfaceRotation == Surface.ROTATION_90) 180 else 0
        val rotation = (imageProxy.imageInfo.rotationDegrees + extra) % 360
        val finalBitmap = if (rotation != 0) {
            val matrix = android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                .also { bitmap.recycle() }
        } else {
            bitmap
        }
        val baos = ByteArrayOutputStream()
        finalBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos)
        finalBitmap.recycle()
        return baos.toByteArray()
    }

    private fun startStreamingThread(serverUrl: String) {
        streamingThread = Thread {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .writeTimeout(0, TimeUnit.SECONDS) // ストリーミング中はタイムアウトなし
                    .readTimeout(0, TimeUnit.SECONDS)
                    .build()

                val requestBody = object : RequestBody() {
                    override fun contentType() =
                        "multipart/x-mixed-replace; boundary=$BOUNDARY".toMediaType()

                    override fun isOneShot() = true

                    override fun writeTo(sink: BufferedSink) {
                        while (isStreaming.get()) {
                            val frame = frameQueue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                            try {
                                sink.writeUtf8("--$BOUNDARY\r\n")
                                sink.writeUtf8("Content-Type: image/jpeg\r\n")
                                sink.writeUtf8("Content-Length: ${frame.size}\r\n")
                                sink.writeUtf8("\r\n")
                                sink.write(frame)
                                sink.writeUtf8("\r\n")
                                sink.flush()
                                frameCount.incrementAndGet()
                            } catch (e: Exception) {
                                if (isStreaming.get()) {
                                    runOnUiThread { showError("送信エラー: ${e.message}") }
                                }
                                break
                            }
                        }
                        // ストリーム終端
                        try {
                            sink.writeUtf8("--$BOUNDARY--\r\n")
                            sink.flush()
                        } catch (_: Exception) {}
                    }
                }

                val request = Request.Builder()
                    .url(serverUrl)
                    .addHeader("User-Agent", "IPCamStreamer/1.0")
                    .post(requestBody)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful && isStreaming.get()) {
                        runOnUiThread {
                            showError("サーバーエラー: HTTP ${response.code}")
                        }
                    }
                }
            } catch (e: InterruptedException) {
                // 停止ボタンによる正常終了
            } catch (e: Exception) {
                if (isStreaming.get()) {
                    runOnUiThread {
                        showError("接続エラー: ${e.message}")
                    }
                }
            }
        }.also { it.isDaemon = true }
        streamingThread?.start()
    }

    private fun startStatusUpdater() {
        mainHandler.post(object : Runnable {
            override fun run() {
                if (isStreaming.get()) {
                    tvFps.text = "FPS: %.1f".format(currentFps)
                    tvFrameCount.text = "送信フレーム数: ${frameCount.get()}"
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

    private fun stopStreaming() {
        isStreaming.set(false)
        frameQueue.clear()
        streamingThread?.interrupt()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        orientationEventListener.disable()
        isStreaming.set(false)
        frameQueue.clear()
        mainHandler.removeCallbacksAndMessages(null)
        cameraExecutor.shutdown()
    }
}
