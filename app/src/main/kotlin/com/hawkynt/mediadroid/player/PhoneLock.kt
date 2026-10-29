package com.hawkynt.mediadroid.player

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * Locking the phone when the darkened player is left (see [DarkPhone]). Android lets an app lock the
 * screen only as a device admin, so the household grants that once — for this one policy, force-lock.
 */
object PhoneLock {

    /** The receiver Android knows the admin by; it needs no code of its own. */
    class Admin : DeviceAdminReceiver()

    private fun admin(context: Context) = ComponentName(context, Admin::class.java)

    /** The household has allowed MediaDroid to lock the phone. */
    fun allowed(context: Context): Boolean =
        context.getSystemService(DevicePolicyManager::class.java).isAdminActive(admin(context))

    /** Android's own screen that asks the household to allow it. */
    fun askIntent(context: Context): Intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
        .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin(context))
        .putExtra(
            DevicePolicyManager.EXTRA_ADD_EXPLANATION,
            "While a film plays on the TV, MediaDroid darkens this phone instead of locking it, as a locked " +
                "phone hides the film. To keep the phone safe, it locks it the moment anyone leaves the player.",
        )

    /** Locks the phone now, when allowed; does nothing otherwise. */
    fun lockNow(context: Context) {
        if (allowed(context)) runCatching { context.getSystemService(DevicePolicyManager::class.java).lockNow() }
    }
}
