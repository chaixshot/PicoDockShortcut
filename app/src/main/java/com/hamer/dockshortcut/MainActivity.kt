package com.hamer.dockshortcut

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
            } catch (e: Exception) {}
        }
        val errThread = Thread {
            try {
                process.errorStream.bufferedReader().use { it.readText() } // Drain
            } catch (e: Exception) {}
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
        // Dumpsys output for services usually uses format: packageName/.service.ClassName
        // We check for the class name part specifically
        val shortName = if (TARGET_SERVICE.contains(".")) TARGET_SERVICE.substringAfterLast(".") else TARGET_SERVICE
        return exec("dumpsys activity services").contains(shortName)
    }
}

// --- ViewModel ---

class MainViewModel : ViewModel() {
    val selectedApps = mutableStateListOf<AppInfo>()
    private val savedApps = mutableStateListOf<AppInfo>()
    
    val isModified by derivedStateOf {
        selectedApps.size != savedApps.size || selectedApps.indices.any { i ->
            !selectedApps[i].isSameAs(savedApps[i])
        }
    }

    var isApplying by mutableStateOf(false)
    var isRetrying by mutableStateOf(false)
    var isModuleActive by mutableStateOf(true)
    var isTargetHooked by mutableStateOf(true)
    var hasRoot by mutableStateOf(true)

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

                val isFitCenterJson = obj.optBoolean("fitCenter", false)
                val appInfo = AppManager.getAppInfo(context, if (isFitCenterJson) FIT_CENTER_PACKAGE else pkg)

                if (appInfo != null) {
                    tempApps.add(
                        appInfo.copy(
                            actionName = if (obj.has("actionName")) obj.getString("actionName") else null,
                            className = if (obj.has("className")) obj.getString("className") else appInfo.className,
                            fitCenter = isFitCenterJson || pkg == FIT_CENTER_PACKAGE
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

    fun removeApp(index: Int) {
        if (index in selectedApps.indices) selectedApps.removeAt(index)
    }

    fun moveApp(from: Int, to: Int) {
        if (from == to || from !in selectedApps.indices || to !in selectedApps.indices) return
        val item = selectedApps.removeAt(from)
        selectedApps.add(to, item)
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
                        put("iconUrl", "Image/custom_icon_${app.packageName}.png")
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
                    Toast.makeText(context, context.getString(R.string.toast_applied_fallback), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

// --- Main Activity ---

class MainActivity : ComponentActivity() {
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
        // Force exit to ensure clean state for module/hooks
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}

// --- UI Components ---

@Composable
fun MainScreen(viewModel: MainViewModel = viewModel()) {
    val context = LocalContext.current
    var showPicker by remember { mutableStateOf(false) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }

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
                Header(viewModel, context)
                Spacer(modifier = Modifier.height(24.dp))
                DockGrid(
                    viewModel = viewModel,
                    onSlotClick = { index ->
                        editingIndex = index
                        showPicker = true
                    },
                    onAddClick = {
                        editingIndex = null
                        showPicker = true
                    }
                )
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
}

@Composable
private fun Header(viewModel: MainViewModel, context: Context) {
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
    onSlotClick: (Int) -> Unit,
    onAddClick: () -> Unit
) {
    val density = LocalDensity.current
    var draggedIndex by remember { mutableStateOf<Int?>(null) }
    var touchPosition by remember { mutableStateOf(Offset.Zero) }
    var touchOffsetWithinItem by remember { mutableStateOf(Offset.Zero) }
    var slotSize by remember { mutableStateOf(Offset.Zero) }
    var gridCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }

    Box(
        modifier = Modifier
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
                        onDelete = { viewModel.removeApp(index) })
                }
            }

            if (viewModel.selectedApps.size < 11) {
                val addIndex = viewModel.selectedApps.size
                item {
                    Box() {
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
                Box() {
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
                ) { DockSlot(app, {}, {}) }
            }
        }
    }
}

@Composable
fun DockSlot(app: AppInfo, onClick: () -> Unit, onDelete: () -> Unit) {
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
    val iconBitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, app.packageName, app.fitCenter) {
        value = withContext(Dispatchers.IO) {
            val drawable = app.icon ?: AppManager.getAppIcon(context, app.packageName)
            drawable?.toBitmap()?.asImageBitmap()
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
    val filtered = remember(query, apps) { apps.filter { it.label.contains(query, true) } }
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
                    // Only show Fit Center option if the package exists on the system
                    val fitCenterInfo = if (AppManager.isPackageInstalled(context, FIT_CENTER_PACKAGE)) {
                        AppManager.getAppInfo(context, FIT_CENTER_PACKAGE)
                    } else null

                    if (fitCenterInfo != null &&
                        fitCenterInfo.label.contains(query, true) &&
                        excludedPackages.none { it == FIT_CENTER_PACKAGE }) {
                        item {
                            AppPickerItem(
                                app = fitCenterInfo,
                                onAppSelected = onAppSelected
                            )
                        }
                    }
                    items(filtered) { app ->
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
    } else if (!viewModel.isModuleActive || !viewModel.isTargetHooked) {
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
