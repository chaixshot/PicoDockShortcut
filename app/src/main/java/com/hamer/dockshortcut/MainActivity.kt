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
import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.animateColorAsState
import androidx.core.os.LocaleListCompat
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hamer.dockshortcut.ui.theme.PicoDockShortcutTheme
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

private object Shell {
    fun exec(command: String): String = try {
        val process = Runtime.getRuntime().exec("su")
        DataOutputStream(process.outputStream).use { os ->
            os.writeBytes("$command\n")
            os.writeBytes("exit\n")
            os.flush()
        }
        val output = process.inputStream.bufferedReader().readText()
        val error = process.errorStream.bufferedReader().readText()
        process.waitFor()
        output + error
    } catch (e: Exception) {
        ""
    }

    // ShortcutService is a bound service (only shows up in dumpsys when a client
    // binds it), so it is NOT a reliable “app is running�?signal. Instead, detect
    // that the target Dock process is actually alive.
    fun isTargetRunning(): Boolean {
        val result = exec("ps -A -o NAME | grep $TARGET_PACKAGE")
        return result.contains(TARGET_PACKAGE) || result.contains("$TARGET_PACKAGE:") || result.contains("$TARGET_PACKAGE ")
    }
}
// --- ViewModel ---

class MainViewModel : ViewModel() {
    val selectedApps = mutableStateListOf<AppInfo>()
    private val savedApps = mutableListOf<AppInfo>()
    
    val isModified by derivedStateOf {
        selectedApps.size != savedApps.size || selectedApps.indices.any { i ->
            !selectedApps[i].isSameAs(savedApps[i])
        }
    }

    var isApplying by mutableStateOf(false)
    var isRetrying by mutableStateOf(false)
    var isModuleActive by mutableStateOf(true)
    var isTargetHooked by mutableStateOf(true)
    var isTargetRunning by mutableStateOf(true)
    var hasRoot by mutableStateOf(true)

    fun checkStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            val isActive = XposedStatus.isActive()
            // Reliable hook detection: Vector/agent injects the module hook classes but the
            // APK path does NOT appear in the target process maps, so grepping maps for
            // "com.hamer.dockshortcut" gives false negatives. Instead we grep the newest
            // LSPosed verbose log for an actual "Hooking com.pvr.shortcut" trace, which is
            // emitted exactly when the module really injected into the target on boot/spawn.
            // We pick the newest verbose_*.log with a plain glob (no command substitution)
            // to avoid timestamp churn when the log rotates on reboot.
            val rootOk = Shell.exec("id").contains("uid=0")
            val runningOk = Shell.isTargetRunning()
            val hookedOk = try {
                Shell.exec(
                    "newest=\$(ls -t /data/adb/lspd/log/verbose_*.log 2>/dev/null | head -1); " +
                        "[ -n \"\$newest\" ] && grep -q \"Hooking $TARGET_PACKAGE\" \"\$newest\" 2>/dev/null && echo HOOKED_OK"
                ).contains("HOOKED_OK")
            } catch (_: Exception) {
                false
            }

            withContext(Dispatchers.Main) {
                isModuleActive = isActive
                hasRoot = rootOk
                isTargetRunning = runningOk
                isTargetHooked = hookedOk
            }
        }
    }

    private fun getJsonFile(context: Context) = File(context.filesDir.parentFile, JSON_FILE_NAME)

    fun loadApps(context: Context) {
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

    private suspend fun parseApps(context: Context, content: String, updateSaved: Boolean) = withContext(Dispatchers.IO) {
        try {
            val jsonArray = JSONArray(content)
            val tempApps = mutableListOf<AppInfo>()

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val pkg = obj.optString("packageName")
                if (pkg == "com.pvr.appmanager") continue

                // 运动中心特殊条目:不走 AppManager, 用合�?AppInfo 展示
                if (obj.optBoolean("fitCenter", false) || pkg == FIT_CENTER_PACKAGE) {
                    tempApps.add(
                        AppInfo(
                            packageName = FIT_CENTER_PACKAGE,
                            className = FIT_CENTER_CLASS,
                            label = FIT_CENTER_LABEL,
                            fitCenter = true,
                            iconUrl = obj.optString("iconUrl").ifEmpty { "Image/custom_icon_${FIT_CENTER_PACKAGE}.png" }
                        )
                    )
                    if (tempApps.size >= 11) break
                    continue
                }

                val appInfo = AppManager.getAppInfo(context, pkg)
                if (appInfo != null) {
                    tempApps.add(
                        appInfo.copy(
                            actionName = if (obj.has("actionName")) obj.getString("actionName") else null,
                            className = if (obj.has("className")) obj.getString("className") else appInfo.className,
                            fitCenter = obj.optBoolean("fitCenter", false) || pkg == FIT_CENTER_PACKAGE,
                            iconUrl = if (obj.has("iconUrl")) obj.getString("iconUrl") else null
                        )
                    )
                } else if (pkg == "com.hamer.debug") {
                    tempApps.add(AppInfo(pkg, null, context.getString(R.string.debug_app_label), null))
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
        }
    }

    fun restoreDefault(context: Context) {
        viewModelScope.launch {
            val default = try {
                context.assets.open(JSON_FILE_NAME).bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                "[]"
            }
            parseApps(context, default, updateSaved = false)

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
            val customFile = File(context.filesDir.parentFile, "Image/Custom/custom_icon_${app.packageName}.png")
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
                            selectedApps[index] = app.copy(iconUrl = "Image/Custom/custom_icon_$packageName.png?t=${System.currentTimeMillis()}")
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
                val item = JSONObject().apply {
                    put("packageName", if (isFitCenter(app)) FIT_CENTER_PACKAGE else app.packageName)
                    app.className?.let { put("className", if (isFitCenter(app)) FIT_CENTER_CLASS else it) }
                    app.actionName?.let { put("actionName", it) }
                    if (isFitCenter(app)) {
                        put("fitCenter", true)
                    }
                    put("iconUrl", app.iconUrl ?: if (isFitCenter(app)) "Image/custom_icon_${FIT_CENTER_PACKAGE}.png" else "Image/custom_icon_${app.packageName}.png")
                }
                put(item)
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
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        // Ensure the process is killed as requested in MainActivity.kt
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    private suspend fun restartTargetApp(context: Context) = withContext(Dispatchers.IO) {
        try {
            // force-stop the Dock so it re-reads dock_fix_apps.json on its next launch.
            // We deliberately do NOT am-start the target: the Dock bar is a system VR
            // panel owned by com.picovr.systemext, force-starting its MainActivity steals
            // the window focus and hides the manager GUI. The config already reloads the
            // next time the Dock is invoked; the user just calls it up with the controller.
            Shell.exec("am force-stop $TARGET_PACKAGE")

            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    "应用成功，按右手柄O呼出dock（约5秒后生效�?,
                    Toast.LENGTH_LONG
                ).show()
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    "应用成功，按右手柄O呼出dock（约5秒后生效�?,
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}

// --- Main Activity ---

class MainActivity : AppCompatActivity() {
    private var lastBackTime: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (lastBackTime + 2000 > System.currentTimeMillis()) finish()
                else {
                    lastBackTime = System.currentTimeMillis()
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.exit_toast),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        })

        setContent {
            PicoDockShortcutTheme {
                MainScreen()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Only force exit if not changing configuration (like locale change)
        if (!isChangingConfigurations) {
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }
}

// --- UI Components ---

@Composable
fun MainScreen(viewModel: MainViewModel = viewModel()) {
    val context = LocalContext.current
    var showPicker by remember { mutableStateOf(false) }
    var showLanguageSelector by remember { mutableStateOf(false) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var pickingIconIndex by remember { mutableStateOf<Int?>(null) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            pickingIconIndex?.let { index ->
                val app = viewModel.selectedApps[index]
                viewModel.saveCustomIcon(context, it, app.packageName)
            }
        }
        pickingIconIndex = null
    }

    LaunchedEffect(Unit) {
        viewModel.loadApps(context)
        viewModel.checkStatus()
    }

    StatusDialogs(viewModel, context)

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(24.dp)),
            color = Color(0xFF292929)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                Header(viewModel, context, onLanguageClick = { showLanguageSelector = true })
                Spacer(modifier = Modifier.height(24.dp))
                // 给网�?weight(1f): 先量完底部背景区, 网格只吃剩下的高�?                // (之前 LazyVerticalGrid �?weight, 会把可用高度全吃�?�?下方按钮被顶出屏�?
                DockGrid(
                    viewModel = viewModel,
                    modifier = Modifier.weight(1f),
                    onSlotClick = { index ->
                        editingIndex = index
                        showPicker = true
                    },
                    onAddClick = {
                        editingIndex = null
                        showPicker = true
                    },
                    onPickIcon = { index ->
                        pickingIconIndex = index
                        imagePickerLauncher.launch("image/*")
                    }
                )
                Spacer(modifier = Modifier.height(16.dp))
                DockBgSection(viewModel, context)
            }
        }
    }

    if (showPicker) {
        val excluded = viewModel.selectedApps.map { it.packageName }.toSet() + "com.pvr.appmanager"
        AppPicker(
            onDismiss = { showPicker = false; editingIndex = null },
            excludedPackages = excluded,
            onAppSelected = { app ->
                editingIndex?.let { idx -> viewModel.selectedApps[idx] = app } ?: viewModel.addApp(
                    app
                )
                showPicker = false
                editingIndex = null
            }
        )
    }

    if (showLanguageSelector) {
        LanguageSelector(onDismiss = { showLanguageSelector = false })
    }
}

@Composable
private fun DockBgSection(viewModel: MainViewModel, context: Context) {
    var bgInfo by remember { mutableStateOf(readBgInfo(context)) }
    var cropUri by remember { mutableStateOf<android.net.Uri?>(null) }

    // Dock 条宽高比。高度固�? 宽度随应用数变化�?    // 裁剪按“上限比例�?11 个应用满�?出图, 左侧对齐; 应用少时右侧内容看不到�?    val barRatio = remember(viewModel.selectedApps.size, bgInfo) {
        dockBarAspect(context, viewModel.selectedApps.size)
    }
    val maxRatio = remember { DOCK_MAX_ASPECT }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) cropUri = uri }

    if (cropUri != null) {
        CropDialog(
            uri = cropUri!!,
            aspect = maxRatio,
            visibleAspect = barRatio,
            appCount = viewModel.selectedApps.size,
            onDismiss = { cropUri = null },
            onConfirm = { cropped ->
                try {
                    val dst = File(context.filesDir.parentFile, "dock_bg.png")
                    dst.outputStream().use { out ->
                        cropped.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                    }
                    try { dst.setReadable(true, false) } catch (_: Throwable) {}
                    bgInfo = readBgInfo(context)
                    cropUri = null
                    Toast.makeText(context, "背景已裁剪保�? $bgInfo", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "保存失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Dock 背景",
                style = MaterialTheme.typography.titleSmall,
                color = Color.LightGray
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                if (bgInfo.isNullOrBlank())
                    "未设置背�?· 选图后可自己框选区�?
                else "当前背景: $bgInfo",
                style = MaterialTheme.typography.bodySmall,
                color = if (bgInfo.isNullOrBlank()) Color.Gray else Color(0xFF7EC8FF)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Dock 高度固定(120dp)、宽度随图标数量变化。图片左侧对齐固�? " +
                "图标越多右侧露出的画面越多。裁剪按系统硬上�?dock_max_width 1800dp " +
                "(${"%.0f".format(maxRatio)} : 1)。当�?${viewModel.selectedApps.size} 个快捷应�?+ 资源�? +
                ", 只能看到左边�?${(barRatio / maxRatio * 100).toInt()}%。打开应用(资源库右侧最�? +
                "$MAX_RECENT_APPS 个最近应�?时会临时变宽, 露出更多�?,
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(
                    text = "选择图片",
                    icon = Icons.Default.Image,
                    containerColor = Color(0xFF1E3C78),
                    disabled = false
                ) { launcher.launch("image/*") }
                ActionButton(
                    text = "应用背景",
                    icon = Icons.Default.Check,
                    containerColor = Color(0xFF2E7D32),
                    disabled = bgInfo.isNullOrBlank()
                ) { viewModel.applyChanges(context, false) }
            }
        }
    }
}

// ---- Dock 条宽高比 ----
// 真实值由 HookInit 写进 Settings.Global("pico_dock_bar_size") = "WxH"�?// 拿不到时�?dock_main_view / dock_left_view / dock_right_view / dock_app_item 布局推算(单位 dp):
//   左区 DockLeftView   = user_icon_margin 16 + user_icon_size 84 + user_icon_margin_left 4
//                      + fit/noti �?44 + app_split_line_width 28 = 176
//   右区 DockRightView  = dock_right_container_margin 8 + dock_right_view_width 114
//                      + dock_right_container_margin_right 16 = 138  (沉浸/IM 块默�?gone)
//   每个图标         = app_icon_width 76 + app_icon_margin 4*2 = 84
//   高度             = main_view_height 120
//   硬上�?          = dock_max_width 1800  => 比例上限 15 : 1
const val MAX_DOCK_APPS = 11        // �?GUI 允许配置的快捷应用上�?不含资源�?
const val MAX_RECENT_APPS = 5       // 资源库右侧的最�?运行中应�?多窗口开启时上限 5)
const val DOCK_MAX_ASPECT = 15f     // dock_max_width 1800 / main_view_height 120

private const val DOCK_SIDE_DP = 176f + 138f
private const val DOCK_ICON_DP = 84f
private const val DOCK_HEIGHT_DP = 120f

// n = 快捷应用个数(不含资源�?; 资源库图标总是存在
private fun dockBarAspectFor(appCount: Int, recentCount: Int = 0): Float {
    val n = (if (appCount > 0) appCount else 5) + 1
    var widthDp = DOCK_SIDE_DP + DOCK_ICON_DP * n
    if (recentCount > 0) widthDp += DOCK_ICON_DP * recentCount + 28f // 分隔�?28
    return (widthDp / DOCK_HEIGHT_DP).coerceAtMost(DOCK_MAX_ASPECT)
}

private fun dockBarAspect(context: Context, appCount: Int): Float {
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
    } catch (_: Throwable) {}
    return dockBarAspectFor(appCount)
}

// ---- 固定比例裁剪对话�? 拖动平移 + 滑杆缩放, 预览即结�?----
// aspect        = 存图比例(上限, �?MAX_DOCK_APPS �?
// visibleAspect = 当前应用数下实际可见的比�? 用于在框内画“当前可见边界�?@Composable
private fun CropDialog(
    uri: android.net.Uri,
    aspect: Float,
    visibleAspect: Float = aspect,
    appCount: Int = 0,
    onDismiss: () -> Unit,
    onConfirm: (android.graphics.Bitmap) -> Unit
) {
    val context = LocalContext.current
    val src = remember(uri) { decodeScaled(context, uri, 3000) }

    if (src == null) {
        LaunchedEffect(Unit) {
            Toast.makeText(context, "图片无法解码", Toast.LENGTH_LONG).show()
            onDismiss()
        }
        return
    }

    val img = remember(src) { src.asImageBitmap() }
    val iw = src.width.toFloat()
    val ih = src.height.toFloat()

    // 缩放 1 = 在图内能取到的最大同比例区域
    val maxCropW: Float
    val maxCropH: Float
    if (iw / ih > aspect) {
        maxCropH = ih; maxCropW = ih * aspect
    } else {
        maxCropW = iw; maxCropH = iw / aspect
    }

    var zoom by remember { mutableFloatStateOf(1f) }
    var cx by remember { mutableFloatStateOf(iw / 2f) }
    var cy by remember { mutableFloatStateOf(ih / 2f) }
    var frameW by remember { mutableFloatStateOf(1f) }
    var frameH by remember { mutableFloatStateOf(1f) }

    val cropW = maxCropW / zoom
    val cropH = maxCropH / zoom
    cx = cx.coerceIn(cropW / 2f, iw - cropW / 2f)
    cy = cy.coerceIn(cropH / 2f, ih - cropH / 2f)

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFF292929),
            modifier = Modifier.fillMaxWidth(0.9f)
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    "框�?Dock 背景区域",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "比例 ${"%.0f".format(aspect)} : 1 (Dock 硬上限宽�? · 原图 ${src.width}x${src.height}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    if (visibleAspect < aspect)
                        "图片左侧对齐。当�?$appCount 个快捷应用只能看到蓝线左侧部�? 图标越多越往右露"
                    else "图片左侧对齐, 图标越多右侧露出的画面越�?,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF7EC8FF)
                )
                Spacer(modifier = Modifier.height(16.dp))

                // 裁剪�?固定比例), 内部直接�?src 矩形绘制 => 所见即所�?                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(aspect)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black)
                        .pointerInput(src, aspect) {
                            detectDragGestures { _, drag ->
                                val kx = cropW / frameW.coerceAtLeast(1f)
                                val ky = cropH / frameH.coerceAtLeast(1f)
                                cx = (cx - drag.x * kx).coerceIn(cropW / 2f, iw - cropW / 2f)
                                cy = (cy - drag.y * ky).coerceIn(cropH / 2f, ih - cropH / 2f)
                            }
                        }
                ) {
                    androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                        frameW = size.width
                        frameH = size.height
                        val sx = (cx - cropW / 2f).coerceAtLeast(0f)
                        val sy = (cy - cropH / 2f).coerceAtLeast(0f)
                        drawImage(
                            image = img,
                            srcOffset = androidx.compose.ui.unit.IntOffset(
                                sx.toInt().coerceIn(0, src.width - 1),
                                sy.toInt().coerceIn(0, src.height - 1)
                            ),
                            srcSize = androidx.compose.ui.unit.IntSize(
                                cropW.toInt().coerceIn(1, src.width - sx.toInt()),
                                cropH.toInt().coerceIn(1, src.height - sy.toInt())
                            ),
                            dstOffset = androidx.compose.ui.unit.IntOffset.Zero,
                            dstSize = androidx.compose.ui.unit.IntSize(
                                size.width.toInt(), size.height.toInt()
                            ),
                            filterQuality = androidx.compose.ui.graphics.FilterQuality.High
                        )
                        // 当前应用数下的可见边�? 右侧变暗 + 一条分界线
                        if (visibleAspect < aspect) {
                            val vw = size.width * (visibleAspect / aspect)
                            drawRect(
                                color = Color.Black.copy(alpha = 0.55f),
                                topLeft = Offset(vw, 0f),
                                size = androidx.compose.ui.geometry.Size(size.width - vw, size.height)
                            )
                            drawLine(
                                color = Color(0xFF7EC8FF),
                                start = Offset(vw, 0f),
                                end = Offset(vw, size.height),
                                strokeWidth = 3f
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ZoomIn, null, tint = Color.LightGray)
                    Spacer(modifier = Modifier.width(8.dp))
                    Slider(
                        value = zoom,
                        onValueChange = { zoom = it },
                        valueRange = 1f..6f,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("${"%.1f".format(zoom)}x", color = Color.LightGray)
                }
                Text(
                    "拖动画面调整取景 · 滑杆放大可只截取局�?,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )

                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Spacer(modifier = Modifier.weight(1f))
                    ActionButton(
                        text = "取消",
                        icon = Icons.Default.Close,
                        containerColor = Color(0xFF444444),
                        disabled = false
                    ) { onDismiss() }
                    ActionButton(
                        text = "使用此区�?,
                        icon = Icons.Default.Check,
                        containerColor = Color(0xFF2E7D32),
                        disabled = false
                    ) {
                        try {
                            val sx = (cx - cropW / 2f).toInt().coerceIn(0, src.width - 1)
                            val sy = (cy - cropH / 2f).toInt().coerceIn(0, src.height - 1)
                            val cw = cropW.toInt().coerceIn(1, src.width - sx)
                            val ch = cropH.toInt().coerceIn(1, src.height - sy)
                            var out = android.graphics.Bitmap.createBitmap(src, sx, sy, cw, ch)
                            // 输出�?Dock 条实际像素的 2 倍即�? 省内�?                            val targetH = 320
                            if (out.height > targetH) {
                                val targetW = (targetH * aspect).toInt().coerceAtLeast(1)
                                out = android.graphics.Bitmap.createScaledBitmap(
                                    out, targetW, targetH, true
                                )
                            }
                            onConfirm(out)
                        } catch (e: Exception) {
                            Toast.makeText(context, "裁剪失败: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }
}

// 按最长边上限解码, 避免大图 OOM
private fun decodeScaled(context: Context, uri: android.net.Uri, maxEdge: Int): android.graphics.Bitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        var sample = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / sample > maxEdge) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    } catch (e: Exception) {
        null
    }
}

// 读模块数据目录里的背景信�?存在则显示文件名+分辨�?
private fun readBgInfo(context: Context): String? {
    return try {
        val f = File(context.filesDir.parentFile, "dock_bg.png")
        if (!f.exists()) return null
        val bmp = BitmapFactory.decodeFile(f.absolutePath)
        if (bmp == null) "dock_bg.png (无法解析)"
        else "dock_bg.png · ${bmp.width}x${bmp.height}"
    } catch (e: Exception) {
        "dock_bg.png (不可�?"
    }
}

@Composable
private fun Header(viewModel: MainViewModel, context: Context, onLanguageClick: () -> Unit = {}) {
    var iconTapCount by remember { mutableIntStateOf(0) }
    
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(70.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        iconTapCount++
                        if (iconTapCount >= 3) {
                            viewModel.applyChanges(context, true)
                            iconTapCount = 0
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.mipmap.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier
                        .size(70.dp)
                        .graphicsLayer(scaleX = 1.4f, scaleY = 1.4f)
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    stringResource(R.string.header_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.LightGray
                )
                Text(
                    stringResource(R.string.header_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.LightGray
                )
                Text(
                    stringResource(R.string.header_instruction_reorder),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.LightGray
                )
                Text(
                    stringResource(R.string.header_instruction_change),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.LightGray
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(
                onClick = onLanguageClick,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Icon(
                    Icons.Default.Language,
                    contentDescription = "Language",
                    tint = Color.LightGray
                )
            }
            ActionButton(
                stringResource(R.string.action_restore),
                Icons.Default.SettingsBackupRestore,
                MaterialTheme.colorScheme.secondaryContainer,
                viewModel.isApplying
            ) {
                viewModel.restoreDefault(context)
            }
            ActionButton(
                stringResource(R.string.action_reload),
                Icons.Default.Refresh,
                MaterialTheme.colorScheme.tertiaryContainer,
                viewModel.isApplying
            ) {
                viewModel.reload(context)
            }
            ActionButton(
                stringResource(R.string.action_apply),
                Icons.Default.Check,
                MaterialTheme.colorScheme.primary,
                viewModel.isApplying || !viewModel.isModified,
                showLoading = viewModel.isApplying
            ) {
                viewModel.applyChanges(context, false)
            }
        }
    }
}

@Composable
private fun ActionButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    containerColor: Color,
    disabled: Boolean,
    showLoading: Boolean = false,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val bgColor by animateColorAsState(if (isHovered) containerColor.copy(alpha = 0.8f) else containerColor)

    Button(
        onClick = onClick,
        enabled = !disabled,
        interactionSource = interactionSource,
        colors = ButtonDefaults.buttonColors(
            containerColor = bgColor,
            contentColor = contentColorFor(containerColor)
        ),
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
    ) {
        if (showLoading && disabled) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = LocalContentColor.current,
                strokeWidth = 2.dp
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.action_wait), style = MaterialTheme.typography.labelLarge)
        } else {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(text)
        }
    }
}

@Composable
private fun DockGrid(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
    onSlotClick: (Int) -> Unit,
    onAddClick: () -> Unit,
    onPickIcon: (Int) -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    var draggedIndex by remember { mutableStateOf<Int?>(null) }
    var touchPosition by remember { mutableStateOf(Offset.Zero) }
    var touchOffsetWithinItem by remember { mutableStateOf(Offset.Zero) }
    var slotSize by remember { mutableStateOf(Offset.Zero) }
    var gridCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { gridCoords = it }) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(6),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            itemsIndexed(
                viewModel.selectedApps,
                key = { _, app -> app.packageName }) { index, app ->
                val currentItemIndex by rememberUpdatedState(index)
                val isDragged = draggedIndex == index
                var itemCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }

                Box(
                    modifier = Modifier
                        .animateItem()
                        .padding(top = if (index >= 6) 24.dp else 0.dp)
                        .onGloballyPositioned {
                            itemCoords = it
                            if (slotSize == Offset.Zero) slotSize =
                                Offset(it.size.width.toFloat(), it.size.height.toFloat())
                        }
                        .graphicsLayer { alpha = if (isDragged) 0f else 1f }
                        .pointerInput(Unit) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { offset ->
                                    val gCoords =
                                        gridCoords ?: return@detectDragGesturesAfterLongPress
                                    val iCoords =
                                        itemCoords ?: return@detectDragGesturesAfterLongPress
                                    draggedIndex = currentItemIndex
                                    touchOffsetWithinItem = offset
                                    touchPosition = gCoords.localPositionOf(iCoords, offset)
                                },
                                onDragEnd = { draggedIndex = null },
                                onDragCancel = { draggedIndex = null },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    touchPosition += dragAmount

                                    val currentIdx =
                                        draggedIndex ?: return@detectDragGesturesAfterLongPress

                                    val spacing = with(density) { 8.dp.toPx() }
                                    
                                    // Calculate target column and row based on touch position relative to grid
                                    val col = (touchPosition.x / (slotSize.x + spacing)).toInt().coerceIn(0, 5)
                                    val row = (touchPosition.y / (slotSize.y + spacing)).toInt().coerceIn(0, 1)
                                    
                                    val targetIdx = (row * 6 + col).coerceIn(0, viewModel.selectedApps.size - 1)

                                    if (targetIdx != currentIdx) {
                                        viewModel.moveApp(currentIdx, targetIdx)
                                        draggedIndex = targetIdx
                                    }
                                }
                            )
                        }
                ) {
                    DockSlot(
                        app,
                        onClick = { onSlotClick(index) },
                        onDelete = { viewModel.removeApp(context, index) },
                        onPickIcon = { onPickIcon(index) })
                }
            }

            if (viewModel.selectedApps.size < 11) {
                val addIndex = viewModel.selectedApps.size
                item {
                    Box(modifier = Modifier.padding(top = if (addIndex >= 6) 24.dp else 0.dp)) {
                        AddSlot(onClick = onAddClick)
                    }
                }
            }

            val fixedIndex =
                viewModel.selectedApps.size + (if (viewModel.selectedApps.size < 11) 1 else 0)
            item {
                val context = LocalContext.current
                val appMgrLabel = stringResource(R.string.app_manager_label)
                val appMgr = remember(appMgrLabel) {
                    AppManager.getAppInfo(context, "com.pvr.appmanager")?.copy(
                        label = appMgrLabel,
                        className = "com.pvr.appmanager.AllAppActivity"
                    )
                }
                Box(modifier = Modifier.padding(top = if (fixedIndex >= 6) 24.dp else 0.dp)) {
                    FixedSlot(appMgr)
                }
            }
        }

        draggedIndex?.let { idx ->
            viewModel.selectedApps.getOrNull(idx)?.let { app ->
                Box(
                    modifier = Modifier
                        .size(
                            with(density) { slotSize.x.toDp() },
                            with(density) { slotSize.y.toDp() })
                        .graphicsLayer {
                            translationX = touchPosition.x - touchOffsetWithinItem.x
                            translationY = touchPosition.y - touchOffsetWithinItem.y
                            scaleX = 1.05f; scaleY = 1.05f
                            shadowElevation = 8.dp.toPx()
                        }
                ) { DockSlot(app, {}, {}, {}) }
            }
        }
    }
}

@Composable
fun DockSlot(app: AppInfo, onClick: () -> Unit, onDelete: () -> Unit, onPickIcon: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp),
        interactionSource = interactionSource,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(
                alpha = 0.8f
            )
        )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Spacer(modifier = Modifier.height(20.dp))
                AppIcon(app)
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    app.label,
                    modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    color = Color.LightGray,
                    textAlign = TextAlign.Center
                )
            }

            val delInteraction = remember { MutableInteractionSource() }
            val isDelHovered by delInteraction.collectIsHoveredAsState()
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(40.dp)
                    .background(
                        if (isDelHovered) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.errorContainer,
                        RoundedCornerShape(bottomStart = 7.dp)
                    )
                    .hoverable(delInteraction)
                    .clickable(onClick = onDelete),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }

            val pickInteraction = remember { MutableInteractionSource() }
            val isPickHovered by pickInteraction.collectIsHoveredAsState()
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .size(40.dp)
                    .background(
                        if (isPickHovered) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.secondaryContainer,
                        RoundedCornerShape(bottomEnd = 7.dp)
                    )
                    .hoverable(pickInteraction)
                    .clickable(onClick = onPickIcon),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Image,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
}

@Composable
fun AddSlot(onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(
                alpha = 0.8f
            )
        )
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                Icons.Default.Add,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
fun FixedSlot(app: AppInfo?) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(
                alpha = 0.5f
            )
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (app != null) {
                Spacer(modifier = Modifier.height(20.dp))
                AppIcon(app)
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    app.label,
                    modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.LightGray,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
            } else {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(54.dp),
                    tint = MaterialTheme.colorScheme.error
                )
                Text(
                    stringResource(R.string.error_no_app_mgr),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
fun AppIcon(app: AppInfo, size: androidx.compose.ui.unit.Dp = 84.dp) {
    val context = LocalContext.current
    val iconBitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, app.packageName, app.fitCenter, app.iconUrl) {
        value = withContext(Dispatchers.IO) {
            val customFile = File(context.filesDir.parentFile, "Image/Custom/custom_icon_${app.packageName}.png")
            if (customFile.exists()) {
                BitmapFactory.decodeFile(customFile.absolutePath)?.asImageBitmap()
            } else {
                val drawable = app.icon ?: AppManager.getAppIcon(context, app.packageName)
                drawable?.toBitmap()?.asImageBitmap()
            }
        }
    }

    if (iconBitmap != null) {
        Image(
            bitmap = iconBitmap!!,
            contentDescription = null,
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(size * 0.18f))
        )
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(size * 0.18f)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (isFitCenter(app)) Icons.Default.FitnessCenter else Icons.Default.Apps,
                contentDescription = null,
                modifier = Modifier.size(size * 0.64f),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPicker(
    onDismiss: () -> Unit,
    excludedPackages: Set<String>,
    onAppSelected: (AppInfo) -> Unit
) {
    val context = LocalContext.current
    val apps by produceState<List<AppInfo>>(emptyList()) {
        value = withContext(Dispatchers.IO) {
            AppManager.getInstalledApps(context).filter { 
                it.packageName !in excludedPackages && it.packageName != FIT_CENTER_PACKAGE 
            }
        }
    }
    var query by remember { mutableStateOf("") }
    // 运动中心作为固定可选项排在列表顶部(若尚未被选入且未被搜索关键字排除)
    val fitOption = remember(excludedPackages) {
        if (FIT_CENTER_PACKAGE in excludedPackages) null
        else AppInfo(FIT_CENTER_PACKAGE, FIT_CENTER_CLASS, FIT_CENTER_LABEL, null, fitCenter = true, iconUrl = "Image/custom_icon_${FIT_CENTER_PACKAGE}.png")
    }
    val filteredAll = remember(query, apps, fitOption) {
        val list = mutableListOf<AppInfo>()
        if (fitOption != null && fitOption.label.contains(query, true)) list.add(fitOption)
        list.addAll(apps.filter { it.label.contains(query, true) })
        list
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(modifier = Modifier.fillMaxHeight(0.9f)) {
            TextField(
                value = query, onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                placeholder = { Text(stringResource(R.string.search_placeholder)) },
                shape = RoundedCornerShape(12.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                )
            )
            if (apps.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 32.dp)
                ) {
                    items(filteredAll) { app ->
                        AppPickerItem(app = app, onAppSelected = onAppSelected)
                    }
                }
            }
        }
    }
}

@Composable
private fun AppPickerItem(app: AppInfo, onAppSelected: (AppInfo) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val isHovered by interaction.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isHovered) MaterialTheme.colorScheme.primaryContainer.copy(
                    alpha = 0.2f
                ) else Color.Transparent
            )
            .hoverable(interaction)
            .clickable { onAppSelected(app) }
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(app, 48.dp)
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            app.label,
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White
        )
    }
}

@Composable
private fun StatusDialogs(viewModel: MainViewModel, context: Context) {
    if (!viewModel.hasRoot) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.dialog_root_title), color = MaterialTheme.colorScheme.error) },
            text = { Text(stringResource(R.string.dialog_root_text)) },
            confirmButton = {
                TextButton(onClick = { viewModel.checkStatus() }) { Text(stringResource(R.string.dialog_retry)) }
                TextButton(onClick = { (context as? android.app.Activity)?.finish() }) { Text(stringResource(R.string.dialog_exit)) }
            },
            containerColor = Color(0xFF333333), textContentColor = Color.White
        )
    } else if (!viewModel.isModuleActive || !viewModel.isTargetHooked || !viewModel.isTargetRunning) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.dialog_warning_title), color = MaterialTheme.colorScheme.error) },
            text = {
                Column {
                    if (!viewModel.isModuleActive) {
                        Text(stringResource(R.string.dialog_warning_lsposed_inactive))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.dialog_warning_lsposed_enable),
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else if (!viewModel.isTargetHooked) {
                        Text(stringResource(R.string.dialog_warning_target_not_hooked))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.dialog_warning_scope_select),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            stringResource(R.string.dialog_warning_reboot),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Root: ${if (viewModel.hasRoot) "OK" else "FAIL"}", style = MaterialTheme.typography.labelSmall)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.restartAndRetry(context) },
                    enabled = !viewModel.isRetrying
                ) {
                    if (viewModel.isRetrying) CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                    else Text(stringResource(R.string.dialog_restart_retry))
                }
                TextButton(onClick = { (context as? android.app.Activity)?.finish() }) { Text(stringResource(R.string.dialog_exit)) }
            },
            containerColor = Color(0xFF333333), textContentColor = Color.White
        )
    }
}

@Preview(showBackground = true, widthDp = 800, heightDp = 480)
@Composable
fun DefaultPreview() {
    PicoDockShortcutTheme { MainScreen() }
}

@Composable
fun LanguageSelector(onDismiss: () -> Unit) {
    val supportedLocales = listOf(
        "Auto" to "",
        "English" to "en",
        "English (UK)" to "en-GB",
        "简体中�? to "zh-CN",
        "繁體中文 (台灣)" to "zh-TW",
        "繁體中文 (香港)" to "zh-HK",
        "Deutsch" to "de",
        "Français" to "fr",
        "Español" to "es",
        "Español (US)" to "es-US",
        "Italiano" to "it",
        "日本�? to "ja",
        "한국�? to "ko",
        "Русский" to "ru",
        "ไท�? to "th",
        "Türkçe" to "tr",
        "Čeština" to "cs",
        "Dansk" to "da",
        "Nederlands" to "nl",
        "Suomi" to "fi",
        "Eλληνικά" to "el",
        "Bahasa Melayu" to "ms",
        "Norsk Bokmål" to "nb",
        "Polski" to "pl",
        "Português (Brasil)" to "pt-BR",
        "Português (Portugal)" to "pt-PT",
        "Română" to "ro",
        "Svenska" to "sv"
    )

    val currentLocale = AppCompatDelegate.getApplicationLocales().toLanguageTags()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Language", color = Color.White) },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                items(supportedLocales) { (name, tag) ->
                    val isSelected = (tag == "" && currentLocale == "") || 
                                   (tag != "" && currentLocale.startsWith(tag))
                    TextButton(
                        onClick = {
                            val appLocale: LocaleListCompat = if (tag.isEmpty()) {
                                LocaleListCompat.getEmptyLocaleList()
                            } else {
                                LocaleListCompat.forLanguageTags(tag)
                            }
                            AppCompatDelegate.setApplicationLocales(appLocale)
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.LightGray
                        )
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(name, textAlign = TextAlign.Start)
                            if (isSelected) {
                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        containerColor = Color(0xFF333333),
        textContentColor = Color.White
    )
}
