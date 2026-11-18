package com.example.screenshare

import android.app.Activity
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.app.ServiceInfo
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.ServiceCompat
import java.io.ByteArrayOutputStream

class ScreenShareService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var server: MjpegServer? = null
    private var captureThread: HandlerThread? = null
    private var lastFrameTime = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        // اگر از قبل در حال اجراست، دوباره راه‌اندازی نکن
        if (running) return START_STICKY

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED

        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DATA)
        }

        // بعد از ری‌استارت شدن توسط سیستم، داده مجوز وجود ندارد
        if (resultCode != Activity.RESULT_OK || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannel()

        // باید قبل از گرفتن MediaProjection فورگراند شود
        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )

        startCapture(resultCode, data)
        running = true

        return START_STICKY
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val proj = mpm.getMediaProjection(resultCode, data)
        projection = proj

        // در اندروید ۱۴ باید قبل از createVirtualDisplay ثبت شود
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, Handler(Looper.getMainLooper()))

        val dm = resources.displayMetrics
        val width = dm.widthPixels / 2
        val height = dm.heightPixels / 2
        val dpi = dm.densityDpi

        val thread = HandlerThread("ScreenCapture").apply { start() }
        captureThread = thread

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader

        val srv = MjpegServer(PORT)
        srv.start()
        server = srv

        reader.setOnImageAvailableListener({ r ->
            onFrame(r, width, height, srv)
        }, Handler(thread.looper))

        virtualDisplay = proj.createVirtualDisplay(
            "ScreenShare",
            width,
            height,
            dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            null
        )
    }

    private fun onFrame(reader: ImageReader, w: Int, h: Int, srv: MjpegServer) {
        val image = reader.acquireLatestImage() ?: return
        try {
            // محدود کردن به حدود ۱۰ فریم در ثانیه
            val now = SystemClock.elapsedRealtime()
            if (now - lastFrameTime < 100) return
            lastFrameTime = now

            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * w

            val bmp = Bitmap.createBitmap(
                w + rowPadding / pixelStride,
                h,
                Bitmap.Config.ARGB_8888
            )
            bmp.copyPixelsFromBuffer(plane.buffer)

            val cropped = Bitmap.createBitmap(bmp, 0, 0, w, h)
            val out = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.JPEG, 60, out)

            srv.updateFrame(out.toByteArray())

            bmp.recycle()
            cropped.recycle()
        } finally {
            image.close()
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannelCompat.Builder(
            CHANNEL_ID,
            NotificationManagerCompat.IMPORTANCE_LOW
        )
            .setName("اشتراک صفحه")
            .build()
        NotificationManagerCompat.from(this).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, ScreenShareService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("اشتراک صفحه فعال است")
            .setContentText("http://${MjpegServer.getLocalIp()}:$PORT")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, "توقف", stopIntent)
            .build()
    }

    private fun cleanup() {
        server?.stop()
        server = null

        virtualDisplay?.release()
        virtualDisplay = null

        imageReader?.close()
        imageReader = null

        projection?.stop()
        projection = null

        captureThread?.quitSafely()
        captureThread = null

        running = false
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.example.screenshare.ACTION_STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val PORT = 8080
        private const val CHANNEL_ID = "screen_share_channel"
        private const val NOTIF_ID = 1

        @Volatile
        var running = false
    }
}