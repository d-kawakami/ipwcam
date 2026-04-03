package com.example.ipwcam

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.textfield.TextInputEditText

class MainActivity : AppCompatActivity() {

    companion object {
        private const val HOST_MODE = "__host_mode__"
        private const val VIEW_MODE = "__view_mode__"
    }

    // 権限取得後に実行するアクションを識別するための値
    // null → QRスキャン(配信用), HOST_MODE → ホスト配信, VIEW_MODE → QRスキャン(受信用), それ以外 → 指定URLへ配信
    private var pendingUrl: String? = null

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                dispatchPendingAction()
            } else {
                Toast.makeText(this, "カメラ権限が必要です", Toast.LENGTH_SHORT).show()
            }
            pendingUrl = null
        }

    // サーバーへの配信用QRスキャン結果
    private val qrScanForStreamLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val serverUrl = result.data?.getStringExtra(QRScanActivity.EXTRA_SERVER_URL)
                if (serverUrl != null) startStreamingActivity(serverUrl)
            }
        }

    // 端末間受信用QRスキャン結果
    private val qrScanForViewLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val streamUrl = result.data?.getStringExtra(QRScanActivity.EXTRA_SERVER_URL)
                if (streamUrl != null) startViewerActivity(streamUrl)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val etServerUrl = findViewById<TextInputEditText>(R.id.etServerUrl)

        // 端末間ダイレクト通信：配信側
        findViewById<Button>(R.id.btnHostQR).setOnClickListener {
            pendingUrl = HOST_MODE
            checkCameraPermission()
        }

        // 端末間ダイレクト通信：受信側
        findViewById<Button>(R.id.btnScanView).setOnClickListener {
            pendingUrl = VIEW_MODE
            checkCameraPermission()
        }

        // Flaskサーバー連携：QRスキャンで配信
        findViewById<Button>(R.id.btnScanQR).setOnClickListener {
            pendingUrl = null
            checkCameraPermission()
        }

        // Flaskサーバー連携：URL手入力で配信
        findViewById<Button>(R.id.btnConnect).setOnClickListener {
            connectWithManualUrl(etServerUrl.text?.toString())
        }

        etServerUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                connectWithManualUrl(etServerUrl.text?.toString())
                true
            } else {
                false
            }
        }
    }

    private fun connectWithManualUrl(url: String?) {
        val trimmed = url?.trim()
        if (trimmed.isNullOrEmpty()) {
            Toast.makeText(this, "URLを入力してください", Toast.LENGTH_SHORT).show()
            return
        }
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            Toast.makeText(this, "URLは http:// または https:// で始めてください", Toast.LENGTH_SHORT).show()
            return
        }
        pendingUrl = trimmed
        checkCameraPermission()
    }

    private fun checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            dispatchPendingAction()
            pendingUrl = null
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun dispatchPendingAction() {
        val url = pendingUrl
        when {
            url == HOST_MODE -> startHostStreamActivity()
            url == VIEW_MODE -> openQRScannerForView()
            url != null      -> startStreamingActivity(url)
            else             -> openQRScannerForStream()
        }
    }

    private fun openQRScannerForStream() {
        qrScanForStreamLauncher.launch(Intent(this, QRScanActivity::class.java))
    }

    private fun openQRScannerForView() {
        qrScanForViewLauncher.launch(Intent(this, QRScanActivity::class.java))
    }

    private fun startStreamingActivity(serverUrl: String) {
        val intent = Intent(this, StreamingActivity::class.java)
        intent.putExtra(StreamingActivity.EXTRA_SERVER_URL, serverUrl)
        startActivity(intent)
    }

    private fun startViewerActivity(streamUrl: String) {
        val intent = Intent(this, ViewerActivity::class.java)
        intent.putExtra(ViewerActivity.EXTRA_STREAM_URL, streamUrl)
        startActivity(intent)
    }

    private fun startHostStreamActivity() {
        startActivity(Intent(this, HostStreamActivity::class.java))
    }
}
