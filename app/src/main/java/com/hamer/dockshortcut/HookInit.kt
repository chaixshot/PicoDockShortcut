package com.hamer.dockshortcut

import android.annotation.SuppressLint
import android.app.AndroidAppHelper
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

class HookInit : IXposedHookLoadPackage {
    private val jsonPath = "/data/user/0/com.hamer.dockshortcut/dock_fix_apps.json"

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == "com.hamer.dockshortcut") {
            XposedHelpers.findAndHookMethod(
                "com.hamer.dockshortcut.XposedStatus",
                lpparam.classLoader,
                "isActive",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = true
                    }
                }
            )
            return
        }

        if (lpparam.packageName != "com.pvr.shortcut") return

        XposedBridge.log("PicoDockShortcut: Hooking com.pvr.shortcut")

        val hook = object : XC_MethodHook() {
            @SuppressLint("DiscouragedPrivateApi")
            override fun beforeHookedMethod(param: MethodHookParam) {
                val fileName = param.args[0] as String
                
                // Intercept the JSON file
                if (fileName == "dock_fix_apps.json" || fileName.endsWith("/dock_fix_apps.json")) {
                    XposedBridge.log("PicoDockShortcut: Intercepting dock_fix_apps.json")
                    try {
                        val file = File(jsonPath)
                        if (file.exists() && file.canRead()) {
                            val content = file.readText()
                            param.result = ByteArrayInputStream(content.toByteArray())
                        } else {
                            XposedBridge.log("PicoDockShortcut: Cannot read $jsonPath")
                        }
                    } catch (e: Exception) {
                        XposedBridge.log("PicoDockShortcut: Error reading JSON: ${e.message}")
                    }
                    return
                }

                // Intercept custom icons
                if (fileName.startsWith("Image/custom_icon_") && fileName.endsWith(".png")) {
                    val pkgName = fileName.substringAfter("Image/custom_icon_").substringBefore(".png")
                    XposedBridge.log("PicoDockShortcut: Providing custom icon for $pkgName")

                    try {
                        val context = AndroidAppHelper.currentApplication()
                        val pm = context.packageManager
                        val icon = pm.getApplicationIcon(pkgName)
                        val bitmap = drawableToBitmap(icon)
                        val stream = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                        param.result = ByteArrayInputStream(stream.toByteArray())
                    } catch (e: Exception) {
                        XposedBridge.log("PicoDockShortcut: Failed to provide icon for $pkgName: ${e.message}")
                    }
                }
            }
        }

        try {
            XposedHelpers.findAndHookMethod(AssetManager::class.java, "open", String::class.java, hook)
            XposedHelpers.findAndHookMethod(AssetManager::class.java, "open", String::class.java, Int::class.javaPrimitiveType, hook)
        } catch (e: Throwable) {
            XposedBridge.log("PicoDockShortcut: Failed to hook AssetManager.open: ${e.message}")
        }
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        val srcW = drawable.intrinsicWidth.coerceAtLeast(1)
        val srcH = drawable.intrinsicHeight.coerceAtLeast(1)

        val targetRatio = 152f / 128f
        val srcRatio = srcW.toFloat() / srcH.toFloat()

        val bitmapW: Int
        val bitmapH: Int

        if (srcRatio < targetRatio) {
            // Source is narrower (e.g. 1:1). Use height as anchor, expand width.
            bitmapH = srcH
            bitmapW = (srcH * 152) / 128
        } else {
            // Source is wider. Use width as anchor, expand height.
            bitmapW = srcW
            bitmapH = (srcW * 128) / 152
        }

        val bitmap = Bitmap.createBitmap(bitmapW, bitmapH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Center the icon in the resulting 152:128 bitmap
        val left = (bitmapW - srcW) / 2
        val top = (bitmapH - srcH) / 2
        drawable.setBounds(left, top, left + srcW, top + srcH)
        drawable.draw(canvas)

        return bitmap
    }
}
