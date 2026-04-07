package com.porter.tvremote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Auto-starts RemoteService after device boot or after the app is updated.
 *
 * Triggers on:
 *   android.intent.action.BOOT_COMPLETED         — normal boot
 *   android.intent.action.MY_PACKAGE_REPLACED    — app self-update
 *
 * Both actions are declared in AndroidManifest.xml.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.i("BootReceiver", "Starting RemoteService after: ${intent.action}")
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, RemoteService::class.java)
                )
            }
        }
    }
}
