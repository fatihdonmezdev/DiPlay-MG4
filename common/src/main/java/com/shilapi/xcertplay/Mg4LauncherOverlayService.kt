package com.shilapi.xcertplay

import android.app.ActivityManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.view.Gravity
import android.view.View
import android.view.WindowManager

internal object Mg4LauncherOverlaySupport {
    /** True when this firmware has a measured stock-launcher CarPlay card to cover. */
    fun isSupported(profile: Mg4FirmwareProfile = Mg4FirmwareProfile.detect()): Boolean =
        profile.stockLauncherPackage != null && profile.launcherCarPlayCard != null
}

/**
 * Replaces the click action of the stock MG4 launcher's Apple CarPlay card.
 *
 * The view is fully transparent: the icon, text, divider and spacing remain the stock
 * launcher's own UI. This service exists only in the platform-signed mobile manifest.
 */
class Mg4LauncherOverlayService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager
    private lateinit var touchTarget: View
    private lateinit var launcherPackage: String
    private lateinit var card: Mg4FirmwareProfile.LauncherCard
    private var attached = false

    private val monitor = object : Runnable {
        override fun run() {
            val shouldShow = isStockLauncherVisible()
            if (shouldShow && !attached) {
                windowManager.addView(touchTarget, layoutParams())
                attached = true
            } else if (!shouldShow && attached) {
                windowManager.removeView(touchTarget)
                attached = false
            }
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val profile = Mg4FirmwareProfile.detect()
        val stockLauncher = profile.stockLauncherPackage
        val stockCard = profile.launcherCarPlayCard
        if (Process.myUid() != Process.SYSTEM_UID || stockLauncher == null || stockCard == null) {
            stopSelf()
            return
        }
        launcherPackage = stockLauncher
        card = stockCard
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        touchTarget = View(this).apply {
            setBackgroundColor(0x00000000)
            isClickable = true
            setOnClickListener { openDiPlay() }
        }
        handler.post(monitor)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    @Suppress("DEPRECATION")
    private fun isStockLauncherVisible(): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val top = manager.getRunningTasks(1).firstOrNull()?.topActivity
        return top?.packageName == launcherPackage
    }

    private fun layoutParams() = WindowManager.LayoutParams(
        card.width,
        card.height,
        WindowManager.LayoutParams.TYPE_SYSTEM_ERROR,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = card.x
        y = card.y
    }

    private fun openDiPlay() {
        startActivity(Intent(this, DiPlayActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        })
    }

    override fun onDestroy() {
        handler.removeCallbacks(monitor)
        if (attached) {
            windowManager.removeView(touchTarget)
            attached = false
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val POLL_INTERVAL_MS = 400L
    }
}
