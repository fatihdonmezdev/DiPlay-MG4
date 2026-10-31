package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log

/** Starts the CarPlay host after boot when the user has enabled the startup option. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in BOOT_ACTIONS) return

        // The MG4 build is platform-signed and runs as android.uid.system. Keep the
        // stock launcher visible while replacing only its Apple CarPlay click action.
        if (Process.myUid() == Process.SYSTEM_UID && Mg4LauncherOverlaySupport.isSupported()) {
            try {
                context.startService(Intent(context, Mg4LauncherOverlayService::class.java))
            } catch (error: RuntimeException) {
                Log.w(TAG, "MG4 launcher overlay could not start", error)
            }
        }

        if (!AirPlayPersistence.loadAutoStartOnBoot(context)) return

        val launch = Intent(context, DiPlayActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        }
        try {
            context.startActivity(launch)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Boot auto-start could not launch CarPlayHostActivity", error)
        }
    }

    private companion object {
        const val TAG = "xcertplay-boot"
        val BOOT_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
        )

    }
}
