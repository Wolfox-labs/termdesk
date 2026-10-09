package dev.termdesk.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * The one thing about background notifications that an app cannot decide for itself.
 *
 * Measured on this device: with a foreground service and a partial wake lock both held, the
 * system still suspended the app in the background — the notification frame sat unread in the
 * connection until the window came back. Delivery is reliable now (the PC re-sends anything the
 * phone has not confirmed), but it is not *timely*, and no amount of code changes that: the
 * permission belongs to the owner, in a settings screen this app is not allowed to flip itself.
 *
 * So it points at the screen. The request intent is the one Android provides for exactly this;
 * an OEM that replaces the dialog (ColorOS does) still lands the owner somewhere they can allow
 * it, and the notification says what to look for when it does not.
 */
object BatteryGuidance {

    /**
     * Whether this app is already allowed to run in the background.
     *
     * `isIgnoringBatteryOptimizations` is the AOSP answer; a manufacturer's own "app freeze"
     * list is invisible from here, so a `true` is not a promise — the notice it controls is
     * worded accordingly rather than claiming everything is fine.
     */
    fun isExempt(context: Context): Boolean {
        val power = context.getSystemService(PowerManager::class.java) ?: return true
        return runCatching { power.isIgnoringBatteryOptimizations(context.packageName) }
            .getOrDefault(true)
    }

    /** The screen to send somebody to, with a fallback for devices that refuse the request. */
    fun intentFor(context: Context): Intent {
        val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
        // Resolving it first: a device that does not handle the request dialog would otherwise
        // show nothing at all when the person taps the one button that was supposed to help.
        val manager = context.packageManager
        if (manager.resolveActivity(request, 0) != null) return request

        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        if (manager.resolveActivity(list, 0) != null) return list

        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))
    }
}
