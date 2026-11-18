package com.porter.tvremote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
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

    companion object {
        private const val TAG = "BootReceiver"

        /**
         * Android 15+ sends an app BOOT_COMPLETED again when it leaves the stopped state, which
         * means the user opened it after a force-stop (AOSP ActivityManagerService
         * maybeSendBootCompletedLocked). That one is no boot: starting the server from it put up
         * "Allow USB debugging?" before anyone pressed Start Server.
         *
         * It is told apart by how the system addresses it: to this package alone, with
         * FLAG_INCLUDE_STOPPED_PACKAGES. The broadcast at boot goes to every app and never to a
         * stopped one, so it carries neither.
         */
        internal fun isLeavingStoppedState(sdkInt: Int, action: String?, targetPackage: String?, flags: Int): Boolean =
            sdkInt >= Build.VERSION_CODES.VANILLA_ICE_CREAM &&
                action == Intent.ACTION_BOOT_COMPLETED &&
                targetPackage != null &&
                (flags and Intent.FLAG_INCLUDE_STOPPED_PACKAGES) != 0
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                if (isLeavingStoppedState(Build.VERSION.SDK_INT, intent.action, intent.`package`, intent.flags)) {
                    // As before Android 15: after a force-stop the server waits for Start Server.
                    Log.i(TAG, "Not starting RemoteService: BOOT_COMPLETED for leaving the stopped state ($intent)")
                    return
                }
                Log.i(TAG, "Starting RemoteService after: ${intent.action}")
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, RemoteService::class.java)
                )
            }
        }
    }
}
