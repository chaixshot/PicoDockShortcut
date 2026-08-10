package com.hamer.picodockshortcut

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

data class AppInfo(
    val packageName: String,
    val className: String?,
    val label: String,
    val icon: Drawable? = null,
    val actionName: String? = null
)

object AppManager {
    fun getInstalledApps(context: Context): List<AppInfo> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = pm.queryIntentActivities(intent, 0)
        return resolveInfos.map {
            AppInfo(
                packageName = it.activityInfo.packageName,
                className = it.activityInfo.name,
                label = it.loadLabel(pm)?.toString() ?: it.activityInfo.packageName,
                icon = it.loadIcon(pm)
            )
        }.sortedBy { it.label.lowercase() }
    }

    fun getAppInfo(context: Context, packageName: String): AppInfo? {
        val pm = context.packageManager
        return try {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            AppInfo(
                packageName = packageName,
                className = null,
                label = pm.getApplicationLabel(appInfo)?.toString() ?: packageName,
                icon = pm.getApplicationIcon(appInfo)
            )
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }
}
