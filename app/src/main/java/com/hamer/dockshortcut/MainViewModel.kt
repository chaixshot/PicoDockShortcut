package com.hamer.dockshortcut

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File

// --- Constants & Shell Utils ---

private const val JSON_FILE_NAME = "dock_fix_apps.json"
private const val TARGET_PACKAGE = "com.pvr.shortcut"
private const val TARGET_SERVICE = "com.pvr.shortcut.service.ShortcutService"
private const val TARGET_ACTION = "pvr.intent.shortcut"

private object Shell {
    fun exec(command: String): String = try {
        val process = Runtime.getRuntime().exec("su")
        DataOutputStream(process.outputStream).use { os ->
            os.writeBytes("$command\n")
            os.writeBytes("exit\n")
            os.flush()
        }

        val output = StringBuilder()
        val outThread = Thread {
            try {
                process.inputStream.bufferedReader().use { output.append(it.readText()) }
            } catch (e: Exception) {
            }
        }
        val errThread = Thread {
            try {
                process.errorStream.bufferedReader().use { it.readText() } // Drain
            } catch (e: Exception) {
            }
        }

        outThread.start()
        errThread.start()

        process.waitFor()
        outThread.join(1000)
        errThread.join(1000)

        output.toString()
    } catch (e: Exception) {
        ""
    }

    fun isProcessRunning(): Boolean {
        return exec("ps -A | grep $TARGET_PACKAGE").contains(TARGET_PACKAGE)
    }

    fun isServiceRunning(): Boolean {
        val shortName =
            if (TARGET_SERVICE.contains(".")) TARGET_SERVICE.substringAfterLast(".") else TARGET_SERVICE
        return exec("dumpsys activity services").contains(shortName)
    }
}

class MainViewModel : ViewModel() {
    val selectedApps = mutableStateListOf<AppInfo>()
    private val savedApps = mutableStateListOf<AppInfo>()

    var isApplying by mutableStateOf(false)
    var isRetrying by mutableStateOf(false)
    var isModuleActive by mutableStateOf(true)
    var isTargetHooked by mutableStateOf(true)
    var hasRoot by mutableStateOf(true)
    var bgPendingRestore by mutableStateOf(false)
    var bgModified by mutableStateOf(false)
    var bgPendingBitmap by mutableStateOf<Bitmap?>(null)

    var pickedImageUri by mutableStateOf<Uri?>(null)

    fun onImagePicked(uri: Uri) {
        pickedImageUri = uri
    }

    fun clearPickedImage() {
        pickedImageUri = null
    }

    var filterUser by mutableStateOf(true)
    var filterSystem by mutableStateOf(false)

    val isModified by derivedStateOf {
        bgPendingRestore || bgModified || selectedApps.size != savedApps.size || selectedApps.indices.any { i ->
            !selectedApps[i].isSameAs(savedApps[i])
        }
    }

    fun checkStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            val isActive = XposedStatus.isActive()
            val cmd = """
                id
                # running = target process alive
                if ps -A -o NAME 2>/dev/null | grep -q "$TARGET_PACKAGE"; then
                    echo "TARGET_RUNNING"
                fi
                # hooked = actual injection trace in newest verbose log
                newest=${'$'}(ls -t /data/adb/lspd/log/verbose_*.log 2>/dev/null | head -1)
                if [ -n "${'$'}newest" ] && grep -q "Hooking $TARGET_PACKAGE" "${'$'}newest" 2>/dev/null; then
                    echo "HOOKED_OK"
                fi
            """.trimIndent()

            val result = Shell.exec(cmd)

            val rootOk = result.contains("uid=0")
            val hookedOk = result.contains("HOOKED_OK")

            withContext(Dispatchers.Main) {
                isModuleActive = isActive
                hasRoot = rootOk
                isTargetHooked = hookedOk
            }
        }
    }

    private fun getJsonFile(context: Context) = File(context.filesDir.parentFile, JSON_FILE_NAME)
    private fun getSettingsFile(context: Context) = File(context.filesDir, "ui_settings.json")

    fun loadSettings(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val file = getSettingsFile(context)
            if (file.exists()) {
                try {
                    val json = JSONObject(file.readText())
                    withContext(Dispatchers.Main) {
                        filterUser = json.optBoolean("filterUser", true)
                        filterSystem = json.optBoolean("filterSystem", false)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun saveSettings(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val json = JSONObject().apply {
                    put("filterUser", filterUser)
                    put("filterSystem", filterSystem)
                }
                getSettingsFile(context).writeText(json.toString())
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun toggleFilterUser(context: Context) {
        filterUser = !filterUser
        saveSettings(context)
    }

    fun toggleFilterSystem(context: Context) {
        filterSystem = !filterSystem
        saveSettings(context)
    }

    fun loadApps(context: Context) {
        loadSettings(context)
        viewModelScope.launch(Dispatchers.IO) {
            val file = getJsonFile(context)
            val content = if (file.exists()) file.readText() else {
                val default = try {
                    context.assets.open(JSON_FILE_NAME).bufferedReader().use { it.readText() }
                } catch (e: Exception) {
                    "[]"
                }
                file.writeText(default)
                file.setReadable(true, false)
                context.filesDir.parentFile?.setExecutable(true, false)
                default
            }
            parseApps(context, content, updateSaved = true)
        }
    }

    private suspend fun parseApps(context: Context, content: String, updateSaved: Boolean) =
        withContext(Dispatchers.IO) {
            try {
                val jsonArray = JSONArray(content)
                val tempApps = mutableListOf<AppInfo>()

                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val pkg = obj.optString("packageName")
                    if (pkg == "com.pvr.appmanager") continue

                    val isFitCenterJson = obj.optBoolean("fitCenter", false)
                    val appInfo = AppManager.getAppInfo(
                        context,
                        if (isFitCenterJson) FIT_CENTER_PACKAGE else pkg
                    )

                    if (appInfo != null) {
                        tempApps.add(
                            appInfo.copy(
                                actionName = if (obj.has("actionName")) obj.getString("actionName") else null,
                                className = if (obj.has("className")) obj.getString("className") else appInfo.className,
                                fitCenter = isFitCenterJson || pkg == FIT_CENTER_PACKAGE,
                                iconUrl = if (obj.has("iconUrl")) obj.getString("iconUrl") else null
                            )
                        )
                    } else if (pkg == "com.hamer.debug") {
                        tempApps.add(
                            AppInfo(
                                pkg,
                                null,
                                context.getString(R.string.debug_app_label),
                                null
                            )
                        )
                    }
                    if (tempApps.size >= 11) break
                }

                if (tempApps.isEmpty()) tempApps.add(
                    AppInfo(
                        "com.hamer.debug",
                        null,
                        context.getString(R.string.debug_app_label),
                        null
                    )
                )

                withContext(Dispatchers.Main) {
                    selectedApps.clear()
                    selectedApps.addAll(tempApps)
                    if (updateSaved) {
                        savedApps.clear()
                        savedApps.addAll(tempApps)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

    fun reload(context: Context) {
        viewModelScope.launch {
            val file = getJsonFile(context)
            if (file.exists()) parseApps(context, file.readText(), updateSaved = true)
            bgPendingRestore = false
            bgModified = false
            bgPendingBitmap = null
        }
    }

    fun restoreDefault(context: Context) {
        viewModelScope.launch {
            val default = try {
                context.assets.open(JSON_FILE_NAME).bufferedReader().use { it.readText() }
            } catch (_: Exception) {
                "[]"
            }
            parseApps(context, default, updateSaved = false)

            bgPendingRestore = true
            bgModified = false
            bgPendingBitmap = null
        }
    }

    private fun clearIconCache(context: Context) {
        val imageDir = File(context.filesDir.parentFile, "Image")
        if (imageDir.exists()) {
            imageDir.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
        }
    }

    fun addApp(app: AppInfo) {
        if (selectedApps.size < 11) selectedApps.add(app)
    }

    fun removeApp(context: Context, index: Int) {
        if (index in selectedApps.indices) {
            val app = selectedApps[index]
            // Delete custom icon if exists
            val customFile =
                File(context.filesDir.parentFile, "Image/Custom/custom_icon_${app.packageName}.png")
            if (customFile.exists()) {
                customFile.delete()
            }
            selectedApps.removeAt(index)
        }
    }

    fun moveApp(from: Int, to: Int) {
        if (from == to || from !in selectedApps.indices || to !in selectedApps.indices) return
        val item = selectedApps.removeAt(from)
        selectedApps.add(to, item)
    }

    fun saveCustomIcon(context: Context, uri: Uri, packageName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                val originalBitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()

                if (originalBitmap != null) {
                    // Process ratio handler
                    val drawable = BitmapDrawable(context.resources, originalBitmap)
                    val processedBitmap = drawableToBitmap(drawable)

                    val imageDir = File(context.filesDir.parentFile, "Image/Custom")
                    if (!imageDir.exists()) {
                        imageDir.mkdirs()
                    }
                    imageDir.setReadable(true, false)
                    imageDir.setExecutable(true, false)

                    val iconFile = File(imageDir, "custom_icon_$packageName.png")
                    iconFile.outputStream().use {
                        processedBitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    iconFile.setReadable(true, false)

                    withContext(Dispatchers.Main) {
                        val index = selectedApps.indexOfFirst { it.packageName == packageName }
                        if (index != -1) {
                            val app = selectedApps[index]
                            selectedApps[index] =
                                app.copy(iconUrl = "Image/Custom/custom_icon_$packageName.png?t=${System.currentTimeMillis()}")
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun saveToJson(context: Context) {
        val jsonArray = JSONArray().apply {
            selectedApps.forEach { app ->
                put(JSONObject().apply {
                    if (isFitCenter(app)) {
                        put("packageName", FIT_CENTER_PACKAGE)
                        put("className", FIT_CENTER_CLASS)
                        put("fitCenter", true)
                    } else {
                        put("packageName", app.packageName)
                        app.className?.let { put("className", it) }
                        app.actionName?.let { put("actionName", it) }

                        val customFile = File(
                            context.filesDir.parentFile,
                            "Image/Custom/custom_icon_${app.packageName}.png"
                        )
                        if (customFile.exists()) {
                            put("iconUrl", "Image/Custom/custom_icon_${app.packageName}.png")
                        } else {
                            put("iconUrl", "Image/custom_icon_${app.packageName}.png")
                        }
                    }
                })
            }
            put(JSONObject().apply {
                put("packageName", "com.pvr.appmanager")
                put("className", "com.pvr.appmanager.AllAppActivity")
                put("iconUrl", "Image/ic_appmanager.png")
            })
        }
        getJsonFile(context).apply {
            writeText(jsonArray.toString(2))
            setReadable(true, false)
        }

        saveIconsToDisk(context)
    }

    private fun saveIconsToDisk(context: Context) {
        val imageDir = File(context.filesDir.parentFile, "Image")
        if (!imageDir.exists()) {
            imageDir.mkdirs()
        }
        imageDir.setReadable(true, false)
        imageDir.setExecutable(true, false)

        selectedApps.forEach { app ->
            if (isFitCenter(app)) return@forEach
            val iconFile = File(imageDir, "custom_icon_${app.packageName}.png")
            if (!iconFile.exists()) {
                val drawable = AppManager.getAppIcon(context, app.packageName)
                if (drawable != null) {
                    val bitmap = drawableToBitmap(drawable)
                    iconFile.outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    iconFile.setReadable(true, false)
                }
            }
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
            bitmapH = srcH
            bitmapW = (srcH * 152) / 128
        } else {
            bitmapW = srcW
            bitmapH = (srcW * 128) / 152
        }

        val bitmap = Bitmap.createBitmap(bitmapW, bitmapH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val left = (bitmapW - srcW) / 2
        val top = (bitmapH - srcH) / 2
        drawable.setBounds(left, top, left + srcW, top + srcH)
        drawable.draw(canvas)
        return bitmap
    }

    fun applyChanges(context: Context, checkStatus: Boolean) {
        viewModelScope.launch {
            isApplying = true
            saveToJson(context)
            clearIconCache(context)
            savedApps.clear()
            savedApps.addAll(selectedApps)

            // Perform background restore or update
            if (bgPendingRestore) {
                withContext(Dispatchers.IO) {
                    val bgFile = File(context.filesDir.parentFile, "dock_bg.png")
                    if (bgFile.exists())
                        bgFile.delete()
                }
            } else if (bgModified && bgPendingBitmap != null) {
                withContext(Dispatchers.IO) {
                    val dst = File(context.filesDir.parentFile, "dock_bg.png")
                    dst.outputStream().use { out ->
                        bgPendingBitmap?.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    try {
                        dst.setReadable(true, false)
                    } catch (_: Throwable) {
                    }
                }
            }
            bgPendingRestore = false
            bgModified = false
            bgPendingBitmap = null

            restartTargetApp(context)
            if (checkStatus) {
                delay(2000) // Give more time for the service to start and module to inject
                checkStatus()
            }
            isApplying = false
        }
    }

    fun restartAndRetry(context: Context) {
        viewModelScope.launch {
            isRetrying = true
            if (!isModuleActive) {
                restartSelf(context)
            } else {
                restartTargetApp(context)
                delay(2000)
                checkStatus()
            }
            isRetrying = false
        }
    }

    private fun restartSelf(context: Context) {
        val packageName = context.packageName
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        val component = intent?.component?.flattenToShortString() ?: "$packageName/.MainActivity"

        if (hasRoot) {
            viewModelScope.launch(Dispatchers.IO) {
                // Background shell script: ensure it continues after this process is killed
                // Force-stop ensures LSPosed re-injects on next start
                Shell.exec("(sleep 0.5; am force-stop $packageName; am start -n $component) &")
            }
        } else {
            intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            context.startActivity(intent)
            Runtime.getRuntime().exit(0)
        }
    }

    private suspend fun restartTargetApp(context: Context) = withContext(Dispatchers.IO) {
        try {
            Shell.exec("am force-stop $TARGET_PACKAGE")
            Shell.exec("am startservice -a $TARGET_ACTION -n $TARGET_PACKAGE/$TARGET_SERVICE")

            var processStarted = false
            var serviceStarted = false

            // Wait for package process first
            repeat(10) {
                if (Shell.isProcessRunning()) {
                    processStarted = true
                    return@repeat
                }
                delay(1000)
            }

            // Wait for specific service
            if (processStarted) {
                repeat(10) {
                    if (Shell.isServiceRunning()) {
                        serviceStarted = true
                        return@repeat
                    }
                    delay(1000)
                }
            }

            withContext(Dispatchers.Main) {
                val msg = when {
                    serviceStarted -> context.getString(R.string.toast_applied_active)
                    processStarted -> context.getString(R.string.toast_applied_slow)
                    else -> context.getString(R.string.toast_applied_timeout)
                }
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.killBackgroundProcesses(TARGET_PACKAGE)
            context.packageManager.getLaunchIntentForPackage(TARGET_PACKAGE)?.let { intent ->
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                withContext(Dispatchers.Main) {
                    context.startActivity(intent)
                    Toast.makeText(
                        context,
                        context.getString(R.string.toast_applied_fallback),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
}
