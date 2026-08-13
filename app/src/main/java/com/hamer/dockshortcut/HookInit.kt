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
import org.json.JSONArray

class HookInit : IXposedHookLoadPackage {
    private val jsonPath = "/data/user/0/com.hamer.dockshortcut/dock_fix_apps.json"
    private val imagePath = "/data/user/0/com.hamer.dockshortcut/Image"

    // True when the user wants the Fit Center shown, i.e. the JSON contains an entry
    // with packageName == com.pvr.fitcenter OR "fitCenter": true.
    private fun fitCenterEnabledInJson(): Boolean {
        return try {
            val file = File(jsonPath)
            if (file.exists() && file.canRead()) {
                val content = file.readText()
                val jsonArray = JSONArray(content)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    if (obj.optBoolean("fitCenter", false) || obj.optString("packageName") == "com.pvr.fitcenter") {
                        return true
                    }
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }

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

        // [Chinese Firmware]
        // Remove the "运动中心" (Fit Center) hard-coded entry from the Dock.
        // addRemoveFitCenterApp(List, List) scans the fix app list and re-inserts
        // AppList.FitCenter when DockUtils.isUserCenterNoFit() is true (China/Phoenix).
        // We neutralize it UNLESS the user enabled Fit Center in the JSON
        // (via an entry with "fitCenter": true) — then we let the system keep it.
        try {
            XposedHelpers.findAndHookMethod(
                "com.pvr.shortcut.dock.datamanager.FixAppDataManager",
                lpparam.classLoader,
                "addRemoveFitCenterApp",
                // MUST pass the exact parameter types here, otherwise Vector resolves
                // addRemoveFitCenterApp() (zero-arg) and fails with #exact mismatch.
                java.util.List::class.java,
                java.util.List::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!fitCenterEnabledInJson()) {
                            param.result = null
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            XposedBridge.log("PicoDockShortcut: Failed to hook addRemoveFitCenterApp: ${e.message}")
        }

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
                if (fileName.startsWith("Image/") && fileName.contains("custom_icon_") && fileName.endsWith(".png")) {
                    val relativePath = fileName.substringAfter("Image/")
                    val pkgName = relativePath.substringAfter("custom_icon_").substringBefore(".png")
                    XposedBridge.log("PicoDockShortcut: Providing custom icon for $pkgName (path: $fileName)")

                    try {
                        // Try loading from cache first
                        val cacheFile = File(imagePath, relativePath)
                        if (cacheFile.exists() && cacheFile.canRead()) {
                            XposedBridge.log("PicoDockShortcut: Loading icon from cache for $pkgName")
                            param.result = ByteArrayInputStream(cacheFile.readBytes())
                            return
                        }

                        // Fallback to generating if cache doesn't exist
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

        // Remove the "运动中心" (Fit Center) hard-coded entry from the Dock.
        // addRemoveFitCenterApp(List, List) scans the fix app list and re-inserts
        // AppList.FitCenter when DockUtils.isUserCenterNoFit() is true (China/Phoenix).
        // We neutralize it UNLESS the user enabled Fit Center in the JSON
        // (via an entry with "fitCenter": true) — then we let the system keep it.
        try {
            XposedHelpers.findAndHookMethod(
                "com.pvr.shortcut.dock.datamanager.FixAppDataManager",
                lpparam.classLoader,
                "addRemoveFitCenterApp",
                java.util.List::class.java,
                java.util.List::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (fitCenterEnabledInJson()) {
                            XposedBridge.log("PicoDockShortcut: Fit Center enabled, allowing addRemoveFitCenterApp")
                            return
                        }
                        XposedBridge.log("PicoDockShortcut: Blocking addRemoveFitCenterApp (remove Fit Center)")
                        param.result = null
                    }
                }
            )
        } catch (e: Throwable) {
            XposedBridge.log("PicoDockShortcut: Failed to hook addRemoveFitCenterApp: ${e.message}")
        }

        installDockBackgroundHook(lpparam.classLoader)
    }

    // ===== Dock 背景定制 =====
    private val bgImgPath = "/data/user/0/com.hamer.dockshortcut/dock_bg.png"

    private fun installDockBackgroundHook(classLoader: ClassLoader?) {
        try {
            val svc = XposedHelpers.findClass("com.pvr.shortcut.service.ShortcutViewContainer", classLoader)
            XposedHelpers.findAndHookMethod(
                svc, "inflateRootView", android.content.Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val ret = param.result
                            if (ret is android.view.ViewGroup) applyDockBg(ret)
                        } catch (t: Throwable) {
                            XposedBridge.log("PicoDockShortcut: inflateRootView hook err " + t)
                        }
                    }
                }
            )
            XposedBridge.log("PicoDockShortcut: Dock background hook installed")
        } catch (e: Throwable) {
            XposedBridge.log("PicoDockShortcut: Dock background hook err " + e.message)
        }
    }

    // 给 Dock 条 + 新手引导(Guide)同步设背景(读用户图片, 无则渐变占位), 每个 View 用自己的圆角
    private fun applyDockBg(root: android.view.ViewGroup) {
        try {
            val bmp = loadUserBitmap()
            if (bmp == null) XposedBridge.log("PicoDockShortcut: no dock_bg.png, using gradient placeholder")

            // 1) Dock 条
            val bar = findDockBar(root)
            if (bar != null) {
                val radii = radiiOf(bar)
                bar.background = RoundedBgDrawable(bmp, radii) { w, h -> writeBarSize(w, h) }
                XposedBridge.log("PicoDockShortcut: SET dock bg on Dock bar (r=" + radii[0] + ")")
                reportBarSize(bar)
            } else {
                XposedBridge.log("PicoDockShortcut: Dock bar NOT found")
            }

            // 2) 新手引导 Guide (id=0x7f09005b) 同步同一张图
            val guide = root.findViewById<android.view.View>(0x7f09005b)
            if (guide != null) {
                val radii2 = radiiOf(guide)
                guide.background = RoundedBgDrawable(bmp, radii2)
                XposedBridge.log("PicoDockShortcut: SET dock bg on Guide (sync r=" + radii2[0] + ")")
            }
        } catch (t: Throwable) {
            XposedBridge.log("PicoDockShortcut: applyDockBg err " + t)
        }
    }

    // Dock 条原生圆角(px)。inflate 时若背景已被替换过读不到, 退回原生值 38。
    private val defaultCornerPx = 38f

    // Dock 条实际宽高上报给 GUI(裁剪框比例用)。
    // 走 Settings.Global: com.pvr.shortcut 是 uid 1000 可写, 模块 App 可读; 比写文件可靠。
    private val barSizeKey = "pico_dock_bar_size"
    private var lastBarSize: String? = null
    private var barCtx: android.content.Context? = null

    private fun reportBarSize(bar: android.view.View) {
        try {
            barCtx = bar.context.applicationContext ?: bar.context
            // 真实布局后上报(用户在 VR 里开过 Dock 就会触发)
            bar.viewTreeObserver.addOnGlobalLayoutListener {
                try { writeBarSize(bar.width, bar.height) } catch (t: Throwable) {}
            }
        } catch (t: Throwable) {
            android.util.Log.i("PicoDockBG", "reportBarSize err " + t.message)
        }
    }

    private fun writeBarSize(w: Int, h: Int) {
        try {
            if (w <= 0 || h <= 0) return
            val txt = "" + w + "x" + h
            if (txt == lastBarSize) return
            lastBarSize = txt
            android.util.Log.i("PicoDockBG", "DOCKBAR SIZE " + txt)
            val ctx = barCtx
            if (ctx != null) {
                android.provider.Settings.Global.putString(ctx.contentResolver, barSizeKey, txt)
            }
        } catch (t: Throwable) {
            android.util.Log.i("PicoDockBG", "writeBarSize err " + t.message)
        }
    }

    private fun radiiOf(v: android.view.View): FloatArray {
        val radii = floatArrayOf(0f,0f,0f,0f,0f,0f,0f,0f)
        val old = v.background
        if (old is android.graphics.drawable.GradientDrawable) {
            val uni = try { old.cornerRadius } catch (t: Throwable) { 0f }
            if (uni > 0f) {
                java.util.Arrays.fill(radii, uni)
                return radii
            }
            try {
                val r = old.cornerRadii
                if (r != null && r.size >= 8 && r.any { it > 0f }) return r.copyOf(8)
            } catch (t: Throwable) {}
        } else if (old is RoundedBgDrawable) {
            return old.radii.copyOf(8)
        }
        java.util.Arrays.fill(radii, defaultCornerPx)
        return radii
    }

    // dock_container(0x7f09009c) 的 LinearLayout 子节点 = Dock 条 (Guide 是 FrameLayout)
    // 这样即使背景已被替换过也能找到, 且绝不会误改 dock_container 本身
    private fun findDockBar(root: android.view.ViewGroup): android.view.View? {
        try {
            val container = root.findViewById<android.view.View>(0x7f09009c)
            if (container is android.view.ViewGroup) {
                for (i in 0 until container.childCount) {
                    val c = container.getChildAt(i)
                    if (c.id != 0x7f09005b && c is android.widget.LinearLayout) return c
                }
            }
        } catch (t: Throwable) {}
        return findVisibleDarkBar(root)
    }

    // 用户图片解码(限制到 <=2048 边长, 省内存)
    private fun loadUserBitmap(): Bitmap? {
        return try {
            val img = File(bgImgPath)
            if (!img.exists() || !img.canRead()) return null
            val bounds = android.graphics.BitmapFactory.Options()
            bounds.inJustDecodeBounds = true
            android.graphics.BitmapFactory.decodeFile(bgImgPath, bounds)
            var sample = 1
            val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
            while (maxDim / sample > 2048) sample *= 2
            val opts = android.graphics.BitmapFactory.Options()
            opts.inSampleSize = sample
            android.graphics.BitmapFactory.decodeFile(bgImgPath, opts)
        } catch (t: Throwable) {
            XposedBridge.log("PicoDockShortcut: bg img decode err " + t.message)
            null
        }
    }

    // 随 View 实际尺寸绘制的圆角背景: inflate 时 width/height 还是 0, 必须在 onBoundsChange 里算
    private class RoundedBgDrawable(
        private val bmp: Bitmap?,
        val radii: FloatArray,
        private val onSize: ((Int, Int) -> Unit)? = null
    ) : android.graphics.drawable.Drawable() {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val path = android.graphics.Path()
        private var built = false

        override fun onBoundsChange(b: android.graphics.Rect) {
            built = false
            build(b)
        }

        private fun build(b: android.graphics.Rect) {
            val w = b.width().toFloat()
            val h = b.height().toFloat()
            if (w <= 0f || h <= 0f) return
            built = true
            onSize?.invoke(b.width(), b.height())
            path.reset()
            path.addRoundRect(android.graphics.RectF(0f, 0f, w, h), radii, android.graphics.Path.Direction.CW)
            paint.shader = if (bmp != null) {
                // 左对齐 + 按高度等比缩放:
                // 图片左侧固定, Dock 变窄时只截掉右侧; Dock 变宽时右侧露出更多画面。
                // 图宽不够时靠 CLAMP 用右边缘像素延展。
                val scale = h / bmp.height
                val m = android.graphics.Matrix()
                m.setScale(scale, scale)
                val sh = android.graphics.BitmapShader(bmp,
                    android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
                sh.setLocalMatrix(m)
                sh
            } else {
                android.graphics.LinearGradient(0f, 0f, w, h,
                    intArrayOf(0xFF1E3C78.toInt(), 0xFFB428A0.toInt(), 0xFFF07828.toInt()),
                    null, android.graphics.Shader.TileMode.CLAMP)
            }
            invalidateSelf()
        }

        override fun draw(canvas: Canvas) {
            // 兜底: 若 onBoundsChange 未触发(首次 setBounds 尺寸为 0 不回调), 绘制时现算
            if (!built) build(bounds)
            if (paint.shader == null) return
            canvas.drawPath(path, paint)
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(cf: android.graphics.ColorFilter?) { paint.colorFilter = cf }
        @Deprecated("deprecated in API 29")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    // 找第一个 bg 为 GradientDrawable 的 LinearLayout = 真正的 Dock 条 (兜底方案)
    private fun findVisibleDarkBar(v: android.view.View): android.view.View? {
        if (v.id != 0x7f09009c
            && v.background is android.graphics.drawable.GradientDrawable
            && v is android.widget.LinearLayout) {
            return v
        }
        if (v is android.view.ViewGroup) {
            val g = v
            for (i in 0 until g.childCount) {
                val r = findVisibleDarkBar(g.getChildAt(i))
                if (r != null) return r
            }
        }
        return null
    }

    // True when the user wants the Fit Center shown, i.e. the JSON contains an entry
    // with packageName == com.pvr.fitcenter OR "fitCenter": true.
    private fun fitCenterEnabledInJson(): Boolean {
        return try {
            val file = File(jsonPath)
            if (!file.exists() || !file.canRead()) return false
            val json = org.json.JSONArray(file.readText())
            for (i in 0 until json.length()) {
                val o = json.getJSONObject(i)
                if (o.optBoolean("fitCenter", false)) return true
                if (o.optString("packageName") == "com.pvr.fitcenter") return true
            }
            false
        } catch (e: Exception) {
            false
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
