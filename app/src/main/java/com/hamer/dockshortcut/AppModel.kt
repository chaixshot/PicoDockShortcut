package com.hamer.dockshortcut

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

data class AppInfo(
    val packageName: String,
    val className: String?,
    val label: String,
    val icon: Drawable? = null,
    val actionName: String? = null,
    val fitCenter: Boolean = false,
    val iconUrl: String? = null,
    val isSystem: Boolean = false
) {
    fun isSameAs(other: AppInfo): Boolean {
        return packageName == other.packageName &&
                className == other.className &&
                actionName == other.actionName &&
                fitCenter == other.fitCenter &&
                iconUrl == other.iconUrl
    }
}

// [Chinese Firmware]
// The "运动中心" (Fit Center) is a hard-coded Dock entry injected by
// FixAppDataManager.addRemoveFitCenterApp, NOT part of the normal JSON app list.
// We give it a synthetic AppInfo so it can be added/removed like any other app,
// but persistence uses the fitCenter flag to drive a special hook rule.
const val FIT_CENTER_PACKAGE = "com.pvr.fitcenter"
const val FIT_CENTER_CLASS = "com.pvr.shortcut.utils.AppList${'$'}FitCenter"

fun isFitCenter(app: AppInfo): Boolean = app.fitCenter || app.packageName == FIT_CENTER_PACKAGE

object AppManager {
    fun getInstalledApps(context: Context): List<AppInfo> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = pm.queryIntentActivities(intent, 0)
        return resolveInfos.map {
            val isSystem = (it.activityInfo.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            AppInfo(
                packageName = it.activityInfo.packageName,
                className = it.activityInfo.name,
                label = it.loadLabel(pm)?.toString() ?: it.activityInfo.packageName,
                icon = null, // Don't load icons here, they are heavy
                isSystem = isSystem
            )
        }.sortedBy { it.label.lowercase() }
    }

    fun getAppIcon(context: Context, packageName: String): Drawable? {
        return try {
            context.packageManager.getApplicationIcon(packageName)
        } catch (e: Exception) {
            null
        }
    }

    fun getAppInfo(context: Context, packageName: String): AppInfo? {
        if (packageName == FIT_CENTER_PACKAGE) {
            val pm = context.packageManager
            val label = try {
                val appInfo = pm.getApplicationInfo(packageName, 0)
                pm.getApplicationLabel(appInfo).toString()
            } catch (e: Exception) {
                context.getString(R.string.fit_center_default_label)
            }
            return AppInfo(
                packageName = FIT_CENTER_PACKAGE,
                className = FIT_CENTER_CLASS,
                label = label,
                fitCenter = true
            )
        }

        val pm = context.packageManager
        return try {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            val isSystem = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            AppInfo(
                packageName = packageName,
                className = null,
                label = pm.getApplicationLabel(appInfo)?.toString() ?: packageName,
                icon = null, // Do not load icon here to speed up startup
                isSystem = isSystem
            )
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    fun isPackageInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }
}
