package com.hamer.dockshortcut.utils

import android.content.Context

private const val DOCK_SIDE_DP = 176f + 138f
private const val DOCK_ICON_DP = 84f
private const val DOCK_HEIGHT_DP = 120f
const val DOCK_MAX_ASPECT = 714f / 47f
const val MAX_RECENT_APPS = 5

// n = number of shortcut apps (excluding library); library icon always exists
private fun dockBarAspectFor(appCount: Int, recentCount: Int = 0): Float {
    val n = (if (appCount > 0) appCount else 5) + 1
    var widthDp = DOCK_SIDE_DP + DOCK_ICON_DP * n
    if (recentCount > 0) widthDp += DOCK_ICON_DP * recentCount + 28f // Separator line 28
    return (widthDp / DOCK_HEIGHT_DP).coerceAtMost(DOCK_MAX_ASPECT)
}

fun dockBarAspect(context: Context, appCount: Int): Float {
    try {
        val s = android.provider.Settings.Global.getString(
            context.contentResolver, "pico_dock_bar_size"
        )
        if (!s.isNullOrBlank()) {
            val p = s.split("x")
            if (p.size == 2) {
                val w = p[0].trim().toFloat()
                val h = p[1].trim().toFloat()
                if (w > 0f && h > 0f) return w / h
            }
        }
    } catch (_: Throwable) {
    }
    return dockBarAspectFor(appCount)
}
