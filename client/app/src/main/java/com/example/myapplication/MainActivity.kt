package com.example.myapplication

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

    // 権限取得後に接続するURL (nullの場合はQRスキャン)
    private var pendingUrl: String? = null

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                val url = pendingUrl
                if (url != null) {
                    startStreamingActivity(url)
                } else {
                    openQRScanner()
                }
            } else {
                Toast.makeText(this, "カメラ権限が必要です", Toast.LENGTH_SHORT).show()
            }
            pendingUrl = null
        }

    private val qrScanLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val serverUrl = result.data?.getStringExtra(QRScanActivity.EXTRA_SERVER_URL)
                if (serverUrl != null) {
                    startStreamingActivity(serverUrl)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val etServerUrl = findViewById<TextInputEditText>(R.id.etServerUrl)

        findViewById<Button>(R.id.btnScanQR).setOnClickListener {
            pendingUrl = null
            checkCameraPermission()
        }

        findViewById<Button>(R.id.btnConnect).setOnClickListener {
            connectWithManualUrl(etServerUrl.text?.toString())
        }

        // キーボードのGoキーでも接続できるようにする
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
            val url = pendingUrl
            if (url != null) {
                startStreamingActivity(url)
                pendingUrl = null
            } else {
                openQRScanner()
            }
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun openQRScanner() {
        qrScanLauncher.launch(Intent(this, QRScanActivity::class.java))
    }

    private fun startStreamingActivity(serverUrl: String) {
        val intent = Intent(this, StreamingActivity::class.java)
        intent.putExtra(StreamingActivity.EXTRA_SERVER_URL, serverUrl)
        startActivity(intent)
    }
}
