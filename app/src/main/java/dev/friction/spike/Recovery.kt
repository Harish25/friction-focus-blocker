package dev.friction.spike

import android.content.Context
import android.content.Intent
import android.telecom.TelecomManager

/** Provisional allowlist, to be confirmed on the target Samsung firmware. */
fun recoveryPackages(context: Context): Set<String> = buildSet {
    add(context.packageName)
    addAll(listOf("android", "com.android.systemui", "com.android.settings",
        "com.android.phone", "com.android.server.telecom", "com.android.emergency",
        "com.samsung.android.dialer", "com.samsung.android.incallui",
        "com.samsung.android.app.telephonyui", "com.sec.android.app.launcher",
        "com.google.android.permissioncontroller", "com.android.permissioncontroller",
        "com.google.android.packageinstaller", "com.android.packageinstaller"))
    val intents = listOf(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
        Intent(Intent.ACTION_DIAL),
        Intent(android.provider.Settings.ACTION_SETTINGS),
    )
    intents.forEach { intent ->
        context.packageManager.queryIntentActivities(intent, 0).forEach { add(it.activityInfo.packageName) }
    }
    context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage?.let { add(it) }
}
