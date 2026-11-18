package com.example.screenshare

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private lateinit var statusText: TextView
    private lateinit var toggleButton: Button

    private val notificationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        requestCapture()
    }

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            val intent = Intent(this, ScreenShareService::class.java)
                .putExtra(ScreenShareService.EXTRA_RESULT_CODE, result.resultCode)
                .putExtra(ScreenShareService.EXTRA_DATA, data)
            ContextCompat.startForegroundService(this, intent)
            toggleButton.postDelayed({ updateUi() }, 500)
        } else {
            Toast.makeText(this, "دسترسی ضبط صفحه داده نشد", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }

        statusText = TextView(this).apply {
            textSize = 18f
            gravity = Gravity.CENTER
        }

        toggleButton = Button(this).apply {
            setOnClickListener { onToggleClicked() }
        }

        root.addView(statusText)
        root.addView(toggleButton)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        updateUi()
    }

    private fun onToggleClicked() {
        if (ScreenShareService.running) {
            val stopIntent = Intent(this, ScreenShareService::class.java)
                .setAction(ScreenShareService.ACTION_STOP)
            startService(stopIntent)
            toggleButton.postDelayed({ updateUi() }, 500)
            return
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }

        requestCapture()
    }

    private fun requestCapture() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        captureLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun updateUi() {
        if (ScreenShareService.running) {
            val ip = MjpegServer.getLocalIp()
            statusText.text =
                "اشتراک صفحه فعال است\n\n" +
                "آدرس مشاهده:\nhttp://$ip:${ScreenShareService.PORT}\n\n" +
                "این آدرس را در مرورگر دستگاه‌های متصل به همان وای‌فای باز کنید."
            toggleButton.text = "توقف اشتراک"
        } else {
            statusText.text = "اشتراک صفحه غیرفعال است"
            toggleButton.text = "شروع اشتراک صفحه"
        }
    }
}