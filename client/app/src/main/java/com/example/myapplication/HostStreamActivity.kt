package com.example.myapplication

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.OrientationEventListener
import android.view.Surface
import android.widget.Button
import android.widget.ImageView
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
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class HostStreamActivity : AppCompatActivity() {

    companion object {
        private const val PORT = 8080
        private const val BOUNDARY = "mjpegstream"
        private const val JPEG_QUALITY = 70
    }

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var serverExecutor: ExecutorService
    private val isStreaming = AtomicBoolean(false)

    // 接続中の各クライアントへのフレームキューリスト
    private val clientQueues = CopyOnWriteArrayList<LinkedBlockingQueue<ByteArray>>()
    private val frameCount = AtomicLong(0)
    private val clientCount = AtomicLong(0)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var imageAnalysis: ImageAnalysis
    private lateinit var orientationEventListener: OrientationEventListener
    @Volatile private var surfaceRotation = Surface.ROTATION_0

    private var lastFpsTime = System.currentTimeMillis()
    private var fpsFrameCount = 0
    private var currentFps = 0.0

    private lateinit var tvServerUrl: TextView
    private lateinit var tvFps: TextView
    private lateinit var tvFrameCount: TextView
    private lateinit var tvClientCount: TextView
    private lateinit var ivQrCode: ImageView

    private var serverSocket: ServerSocket? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_host_stream)

        tvServerUrl = findViewById(R.id.tvServerUrl)
        tvFps = findViewById(R.id.tvFps)
        tvFrameCount = findViewById(R.id.tvFrameCount)
        tvClientCount = findViewById(R.id.tvClientCount)
        ivQrCode = findViewById(R.id.ivQrCode)

        val ip = getLocalIpAddress()
        if (ip == null) {
            Toast.makeText(this, "WiFiまたはLANに接続してください", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val streamUrl = "http://$ip:$PORT/stream"
        tvServerUrl.text = streamUrl
        showQrCode(streamUrl)

        cameraExecutor = Executors.newSingleThreadExecutor()
        serverExecutor = Executors.newCachedThreadPool()
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
                val targetRot = if (surfaceRotation == Surface.ROTATION_90 || surfaceRotation == Surface.ROTATION_270)
                    Surface.ROTATION_90 else surfaceRotation
                if (::imageAnalysis.isInitialized) {
                    imageAnalysis.targetRotation = targetRot
                }
            }
        }
        orientationEventListener.enable()

        startCamera()
        startHttpServer()
        startStatusUpdater()

        findViewById<Button>(R.id.btnStop).setOnClickListener {
            stopStreaming()
        }
    }

    /** NetworkInterfaceから有線/WiFiのIPv4アドレスを取得 */
    private fun getLocalIpAddress(): String? {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        for (intf in interfaces) {
            if (!intf.isUp || intf.isLoopback) continue
            for (addr in intf.inetAddresses) {
                if (!addr.isLoopbackAddress && addr is Inet4Address) {
                    return addr.hostAddress
                }
            }
        }
        return null
    }

    private fun showQrCode(content: String) {
        try {
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, 512, 512)
            val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.RGB_565)
            for (x in 0 until 512) {
                for (y in 0 until 512) {
                    bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            ivQrCode.setImageBitmap(bitmap)
        } catch (e: Exception) {
            Toast.makeText(this, "QRコード生成エラー: ${e.message}", Toast.LENGTH_SHORT).show()
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
                if (isStreaming.get() && clientQueues.isNotEmpty()) {
                    val jpegBytes = imageProxyToJpeg(imageProxy)
                    // 全クライアントのキューへ配信
                    for (queue in clientQueues) {
                        if (queue.size >= 3) queue.poll()
                        queue.offer(jpegBytes)
                    }
                    fpsFrameCount++
                    val now = System.currentTimeMillis()
                    if (now - lastFpsTime >= 1000) {
                        currentFps = fpsFrameCount * 1000.0 / (now - lastFpsTime)
                        fpsFrameCount = 0
                        lastFpsTime = now
                    }
                    frameCount.incrementAndGet()
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
                    Toast.makeText(this, "カメラエラー: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun imageProxyToJpeg(imageProxy: ImageProxy): ByteArray {
        val bitmap = imageProxy.toBitmap()
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

    private fun startHttpServer() {
        serverExecutor.execute {
            try {
                val ss = ServerSocket(PORT).also { serverSocket = it }
                ss.soTimeout = 1000 // accept()のタイムアウト(ループ終了チェック用)
                while (isStreaming.get()) {
                    try {
                        val socket = ss.accept()
                        serverExecutor.execute { handleClient(socket) }
                    } catch (_: java.net.SocketTimeoutException) {
                        // タイムアウトは正常 → isStreamingを再チェック
                    } catch (e: Exception) {
                        if (isStreaming.get()) {
                            runOnUiThread {
                                Toast.makeText(this, "サーバーエラー: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                ss.close()
            } catch (e: Exception) {
                if (isStreaming.get()) {
                    runOnUiThread {
                        Toast.makeText(this, "ポート $PORT の起動に失敗: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 5000
            val reader = socket.getInputStream().bufferedReader()
            val output = socket.getOutputStream()

            // HTTPリクエストの1行目を読む
            val requestLine = reader.readLine() ?: return
            // ヘッダーを読み捨て
            var line = reader.readLine()
            while (!line.isNullOrEmpty()) {
                line = reader.readLine()
            }

            val path = requestLine.split(" ").getOrElse(1) { "/" }
            if (path == "/stream" || path == "/") {
                serveStream(socket, output)
            } else {
                // その他のパスには404を返す
                output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
                output.flush()
            }
        } catch (_: Exception) {
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun serveStream(socket: Socket, output: OutputStream) {
        socket.soTimeout = 0
        val queue = LinkedBlockingQueue<ByteArray>(5)
        clientQueues.add(queue)
        clientCount.incrementAndGet()
        runOnUiThread { tvClientCount.text = "視聴中: ${clientCount.get()}台" }

        try {
            // MJPEGストリーム用HTTPレスポンスヘッダー
            output.write(
                ("HTTP/1.1 200 OK\r\n" +
                "Content-Type: multipart/x-mixed-replace; boundary=$BOUNDARY\r\n" +
                "Cache-Control: no-cache\r\n" +
                "Connection: close\r\n\r\n").toByteArray()
            )
            output.flush()

            while (isStreaming.get() && !socket.isClosed) {
                val frame = queue.poll(1, TimeUnit.SECONDS) ?: continue
                val header = "--$BOUNDARY\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n"
                output.write(header.toByteArray())
                output.write(frame)
                output.write("\r\n".toByteArray())
                output.flush()
            }
        } catch (_: Exception) {
            // クライアント切断
        } finally {
            clientQueues.remove(queue)
            clientCount.decrementAndGet()
            runOnUiThread { tvClientCount.text = "視聴中: ${clientCount.get()}台" }
        }
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

    private fun stopStreaming() {
        isStreaming.set(false)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        orientationEventListener.disable()
        isStreaming.set(false)
        mainHandler.removeCallbacksAndMessages(null)
        cameraExecutor.shutdown()
        serverExecutor.shutdownNow()
        try { serverSocket?.close() } catch (_: Exception) {}
    }
}
