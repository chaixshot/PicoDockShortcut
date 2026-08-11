package com.hamer.dockshortcut

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material.icons.filled.Check
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
import kotlin.math.roundToInt

class MainViewModel : ViewModel() {
    var selectedApps = mutableStateListOf<AppInfo>(
        AppInfo("com.hamer.debug", null, "Debug App", null)
    )
    var isApplying by mutableStateOf(false)
    var isModuleActive by mutableStateOf(true)
    var isTargetAppHooked by mutableStateOf(true)
    var hasRootAccess by mutableStateOf(true)
    private val jsonFileName = "dock_fix_apps.json"

    fun checkStatus() {
        viewModelScope.launch {
            val isActive = XposedStatus.isActive()
            val hasRoot = withContext(Dispatchers.IO) { checkRoot() }
            val isHooked = if (hasRoot) withContext(Dispatchers.IO) { checkIfTargetIsHooked() } else false
            
            isModuleActive = isActive
            hasRootAccess = hasRoot
            isTargetAppHooked = isHooked
        }
    }

    private fun checkRoot(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su -c id")
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            output.contains("uid=0")
        } catch (e: Exception) {
            false
        }
    }

    private fun checkIfTargetIsHooked(): Boolean {
        val targetPackage = "com.pvr.shortcut"
        val myPackage = "com.hamer.dockshortcut"
        return try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            // Check if any process of the target app has our module in its memory maps
            val cmd = "for pid in $(pidof $targetPackage); do grep -q \"$myPackage\" /proc/\"\$pid\"/maps && echo \"HOOKED_OK\" && break; done\n"
            os.writeBytes(cmd)
            os.writeBytes("exit\n")
            os.flush()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            output.contains("HOOKED_OK")
        } catch (e: Exception) {
            // Fallback: if we can't check via root, assume it's hooked if module is active for UI app
            // Or just return false to be safe.
            false
        }
    }

    private fun getJsonFile(context: Context): File {
        return File(context.filesDir.parentFile, jsonFileName)
    }

    fun loadApps(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val file = getJsonFile(context)
            val content = if (file.exists()) {
                file.readText()
            } else {
                val defaultContent = try {
                    context.assets.open(jsonFileName).bufferedReader().use { it.readText() }
                } catch (e: Exception) {
                    "[]"
                }
                file.writeText(defaultContent)
                file.setReadable(true, false)
                context.filesDir.parentFile?.setExecutable(true, false)
                defaultContent
            }
            parseApps(context, content)
        }
    }

    private fun parseApps(context: Context, content: String) {
        try {
            val jsonArray = JSONArray(content)
            val tempApps = mutableListOf<AppInfo>()

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val pkg = obj.optString("packageName")
                if (pkg == "com.pvr.appmanager") continue
                if (tempApps.size < 11) {
                    val appInfo = AppManager.getAppInfo(context, pkg)
                    if (appInfo != null) {
                        tempApps.add(appInfo.copy(
                            actionName = if (obj.has("actionName")) obj.getString("actionName") else null,
                            className = if (obj.has("className")) obj.getString("className") else appInfo.className
                        ))
                    } else if (pkg == "com.hamer.debug") {
                        tempApps.add(AppInfo(pkg, null, "Debug App", null))
                    }
                }
            }

            // If empty, add debug app as placeholder
            if (tempApps.isEmpty()) {
                tempApps.add(AppInfo("com.hamer.debug", null, "Debug App", null))
            }

            selectedApps.clear()
            selectedApps.addAll(tempApps)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun reload(context: Context) {
        val file = getJsonFile(context)
        if (file.exists()) {
            parseApps(context, file.readText())
        }
    }

    fun restoreDefault(context: Context) {
        val defaultContent = try {
            context.assets.open(jsonFileName).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            "[]"
        }
        parseApps(context, defaultContent)
    }

    fun addApp(app: AppInfo) {
        if (selectedApps.size < 11) {
            selectedApps.add(app)
        }
    }

    fun removeApp(index: Int) {
        if (index in selectedApps.indices) {
            selectedApps.removeAt(index)
        }
    }

    fun moveApp(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex || fromIndex !in selectedApps.indices || toIndex !in selectedApps.indices) return
        val item = selectedApps.removeAt(fromIndex)
        selectedApps.add(toIndex, item)
    }

    fun saveToJson(context: Context) {
        val jsonArray = JSONArray()
        selectedApps.forEach { app ->
            val obj = JSONObject()
            obj.put("packageName", app.packageName)
            if (app.className != null) obj.put("className", app.className)
            if (app.actionName != null) obj.put("actionName", app.actionName)
            obj.put("iconUrl", "Image/custom_icon_${app.packageName}.png")
            jsonArray.put(obj)
        }
        val appManager = JSONObject()
        appManager.put("packageName", "com.pvr.appmanager")
        appManager.put("className", "com.pvr.appmanager.AllAppActivity")
        appManager.put("iconUrl", "Image/ic_appmanager.png")
        jsonArray.put(appManager)

        val file = getJsonFile(context)
        file.writeText(jsonArray.toString(2))
        file.setReadable(true, false)
    }

    fun applyChanges(context: Context) {
        viewModelScope.launch {
            isApplying = true
            saveToJson(context)
            restartTargetApp(context)
            checkStatus()
            isApplying = false
        }
    }

    fun restartAndRetry(context: Context) {
        viewModelScope.launch {
            restartTargetApp(context)
            checkStatus()
        }
    }

    private suspend fun restartTargetApp(context: Context) = withContext(Dispatchers.IO) {
        val packageName = "com.pvr.shortcut"
        val serviceName = "com.pvr.shortcut.service.ShortcutService"
        val action = "pvr.intent.shortcut"
        try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            os.writeBytes("am force-stop $packageName\n")
            // Start the ShortcutService directly via shell
            os.writeBytes("am startservice -a $action -n $packageName/$serviceName\n")
            os.writeBytes("exit\n")
            os.flush()
            process.waitFor()

            // Wait for service to be started
            var started = false
            for (i in 0 until 10) {
                if (isServiceStarted(serviceName)) {
                    started = true
                    break
                }
                delay(1000)
            }

            withContext(Dispatchers.Main) {
                if (started) {
                    Toast.makeText(context, "Applied & Service Started", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Applied (Service check timed out)", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.killBackgroundProcesses(packageName)
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                withContext(Dispatchers.Main) {
                    context.startActivity(intent)
                    Toast.makeText(context, "Applied (Launch fallback)", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun isServiceStarted(serviceName: String): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su -c \"dumpsys activity services | grep $serviceName\"")
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            output.contains(serviceName)
        } catch (e: Exception) {
            false
        }
    }
}

class MainActivity : ComponentActivity() {
    private var lastBackTime: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (lastBackTime + 2000 > System.currentTimeMillis()) {
                    finish()
                } else {
                    lastBackTime = System.currentTimeMillis()
                    Toast.makeText(this@MainActivity, "Press once again to Exit", Toast.LENGTH_SHORT).show()
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
        System.exit(0)
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}

@Composable
fun hoverButtonColors(
    normalColor: Color,
    hoverColor: Color,
    contentColor: Color,
    interactionSource: MutableInteractionSource
): ButtonColors {
    val isHovered by interactionSource.collectIsHoveredAsState()
    val backgroundColor by animateColorAsState(
        targetValue = if (isHovered) hoverColor else normalColor,
        label = "buttonHover"
    )
    return ButtonDefaults.buttonColors(
        containerColor = backgroundColor,
        contentColor = contentColor,
        disabledContainerColor = backgroundColor.copy(alpha = 0.5f)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel = viewModel()) {
    val context = LocalContext.current
    var showPicker by remember { mutableStateOf(false) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(Unit) {
        viewModel.loadApps(context)
        viewModel.checkStatus()
    }

    if (!viewModel.isModuleActive || !viewModel.isTargetAppHooked) {
        AlertDialog(
            onDismissRequest = { },
            title = {
                Text(
                    text = "System Warning",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
            },
            text = {
                Column {
                    if (!viewModel.isModuleActive) {
                        Text("• LSPosed module is not active. Please enable it in LSPosed Manager.")
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    if (!viewModel.isTargetAppHooked) {
                        Text("• App Dock (com.pvr.shortcut) is not selected in scope or not running.")
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Please ensure the target app is checked in LSPosed Manager and then click 'Restart Dock & Retry'.")
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = Color.Gray, thickness = 0.5.dp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Module Active: ${if (viewModel.isModuleActive) "Yes" else "No"}",
                        color = if (viewModel.isModuleActive) Color.Green else Color.Red)
                    Text("Target Hooked: ${if (viewModel.isTargetAppHooked) "Yes" else "No"}",
                        color = if (viewModel.isTargetAppHooked) Color.Green else Color.Red)
                }
            },
            confirmButton = {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (viewModel.isModuleActive && !viewModel.isTargetAppHooked) {
                        TextButton(onClick = { viewModel.restartAndRetry(context) }) {
                            Text("Restart Dock & Retry")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    else if (!viewModel.isModuleActive)
                    {
                        TextButton(onClick = {
                            (context as? android.app.Activity)?.finishAffinity()
                            android.os.Process.killProcess(android.os.Process.myPid())
                        }) {
                            Text("Exit")
                        }
                    }
                }
            },
            containerColor = Color(0xFF333333),
            textContentColor = Color.White
        )
    }

    // Root Container
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent),
        contentAlignment = Alignment.Center
    ) {
        // Main Container
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(24.dp)),
            color = Color(0xFF292929),
            tonalElevation = 2.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header
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
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                painter = painterResource(id = R.mipmap.ic_launcher_foreground),
                                contentDescription = "App Icon",
                                modifier = Modifier
                                    .size(70.dp)
                                    .graphicsLayer(scaleX = 1.4f, scaleY = 1.4f)
                            )
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Column {
                            Text(
                                text = "Dock Shortcut Manager",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.LightGray,
                            )
                            Text(
                                text = "Manage your Pico 4 dock pinned shortcuts",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.LightGray,
                            )
                            Text(
                                text = "- Hold and drag the app to reorder",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.LightGray,
                            )
                            Text(
                                text = "- Tab the existing app to change the app",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.LightGray,
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val restoreInteraction = remember { MutableInteractionSource() }
                        Button(
                            onClick = { viewModel.restoreDefault(context) },
                            enabled = !viewModel.isApplying,
                            interactionSource = restoreInteraction,
                            colors = hoverButtonColors(
                                normalColor = MaterialTheme.colorScheme.secondaryContainer,
                                hoverColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.8f),
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                interactionSource = restoreInteraction
                            ),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.SettingsBackupRestore, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Restore", style = MaterialTheme.typography.labelLarge)
                        }

                        val reloadInteraction = remember { MutableInteractionSource() }
                        Button(
                            onClick = { viewModel.reload(context) },
                            enabled = !viewModel.isApplying,
                            interactionSource = reloadInteraction,
                            colors = hoverButtonColors(
                                normalColor = MaterialTheme.colorScheme.tertiaryContainer,
                                hoverColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.8f),
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                interactionSource = reloadInteraction
                            ),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Reload", style = MaterialTheme.typography.labelLarge)
                        }

                        val applyInteraction = remember { MutableInteractionSource() }
                        Button(
                            onClick = { viewModel.applyChanges(context) },
                            enabled = !viewModel.isApplying,
                            interactionSource = applyInteraction,
                            colors = hoverButtonColors(
                                normalColor = MaterialTheme.colorScheme.primary,
                                hoverColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                                interactionSource = applyInteraction
                            ),
                            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (viewModel.isApplying) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Restarting...", style = MaterialTheme.typography.labelLarge)
                            } else {
                                Icon(Icons.Default.Check, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Apply", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Dock Grid
                var draggedIndex by remember { mutableStateOf<Int?>(null) }
                var touchPosition by remember { mutableStateOf(Offset.Zero) }
                var touchOffsetWithinItem by remember { mutableStateOf(Offset.Zero) }
                var slotSize by remember { mutableStateOf(Offset.Zero) }

                var gridCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
                var draggedItemCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
                val density = LocalDensity.current

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { gridCoordinates = it }
                ) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(6),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Selected Apps
                        itemsIndexed(
                            items = viewModel.selectedApps,
                            key = { _, app -> app.packageName }
                        ) { index, app ->
                            val isDragged = draggedIndex == index
                            val currentDraggingIndex by rememberUpdatedState(index)
                            var itemCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }

                            Box(
                                modifier = Modifier
                                    .animateItem()
                                    .onGloballyPositioned { coordinates ->
                                        itemCoordinates = coordinates
                                        if (slotSize == Offset.Zero) {
                                            slotSize = Offset(coordinates.size.width.toFloat(), coordinates.size.height.toFloat())
                                        }
                                        if (isDragged) {
                                            draggedItemCoordinates = coordinates
                                        }
                                    }
                                    .graphicsLayer {
                                        // Hide item in grid when dragging (it will be drawn in the Overlay Box instead)
                                        alpha = if (isDragged) 0f else 1f
                                    }
                                    .pointerInput(Unit) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = { offset ->
                                                val gridCoords = gridCoordinates ?: return@detectDragGesturesAfterLongPress
                                                val itemCoords = itemCoordinates ?: return@detectDragGesturesAfterLongPress

                                                draggedIndex = currentDraggingIndex
                                                draggedItemCoordinates = itemCoords
                                                touchOffsetWithinItem = offset
                                                touchPosition = gridCoords.localPositionOf(itemCoords, offset)
                                            },
                                            onDragEnd = {
                                                draggedIndex = null
                                            },
                                            onDragCancel = {
                                                draggedIndex = null
                                            },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                touchPosition += dragAmount

                                                val gridCoords = gridCoordinates ?: return@detectDragGesturesAfterLongPress
                                                val itemCoords = draggedItemCoordinates ?: return@detectDragGesturesAfterLongPress
                                                val currentItemTopLeft = gridCoords.localPositionOf(itemCoords, Offset.Zero)
                                                val currentDragOffset = (touchPosition - touchOffsetWithinItem) - currentItemTopLeft

                                                val spacing = with(density) { 8.dp.toPx() }
                                                val colOffset = (currentDragOffset.x / (slotSize.x + spacing)).roundToInt()
                                                val rowOffset = (currentDragOffset.y / (slotSize.y + spacing)).roundToInt()

                                                val currentCol = currentDraggingIndex % 6
                                                val currentRow = currentDraggingIndex / 6

                                                val targetCol = (currentCol + colOffset).coerceIn(0, 5)
                                                val targetRow = (currentRow + rowOffset).coerceIn(0, 1)
                                                val targetIndex = (targetRow * 6 + targetCol).coerceIn(0, viewModel.selectedApps.size - 1)

                                                if (targetIndex != currentDraggingIndex) {
                                                    viewModel.moveApp(currentDraggingIndex, targetIndex)
                                                    draggedIndex = targetIndex
                                                }
                                            }
                                        )
                                    }
                            ) {
                                DockSlot(
                                    app = app,
                                    onClick = {
                                        editingIndex = index
                                        showPicker = true
                                    },
                                    onDelete = { viewModel.removeApp(index) }
                                )
                            }
                        }

                        // Add Button (if less than 11)
                        if (viewModel.selectedApps.size < 11) {
                            item {
                                AddSlot(onAdd = {
                                    editingIndex = null
                                    showPicker = true
                                })
                            }
                        }

                        // Fixed App Manager
                        item {
                            val appManagerInfo = remember {
                                AppManager.getAppInfo(context, "com.pvr.appmanager")?.copy(
                                    label = "App Manager",
                                    className = "com.pvr.appmanager.AllAppActivity"
                                )
                            }
                            FixedSlot(app = appManagerInfo)
                        }
                    }

                    // Floating Dragged Overlay Item
                    draggedIndex?.let { index ->
                        viewModel.selectedApps.getOrNull(index)?.let { app ->
                            val xOffset = touchPosition.x - touchOffsetWithinItem.x
                            val yOffset = touchPosition.y - touchOffsetWithinItem.y

                            Box(
                                modifier = Modifier
                                    .size(
                                        width = with(density) { slotSize.x.toDp() },
                                        height = with(density) { slotSize.y.toDp() }
                                    )
                                    .graphicsLayer {
                                        translationX = xOffset
                                        translationY = yOffset
                                        scaleX = 1.05f
                                        scaleY = 1.05f
                                        shadowElevation = 16.dp.toPx()
                                        clip = false
                                    }
                            ) {
                                DockSlot(app = app, onClick = {}, onDelete = {})
                            }
                        }
                    }
                }
            }
        }
    }

    if (showPicker) {
        val selectedPackages = viewModel.selectedApps.map { it.packageName }.toSet() + "com.pvr.appmanager"
        AppPicker(
            onDismiss = {
                showPicker = false
                editingIndex = null
            },
            excludedPackages = selectedPackages,
            onAppSelected = { app ->
                val index = editingIndex
                if (index != null && index in viewModel.selectedApps.indices) {
                    viewModel.selectedApps[index] = app
                } else {
                    viewModel.addApp(app)
                }
                showPicker = false
                editingIndex = null
            }
        )
    }
}

@Composable
fun DockSlot(app: AppInfo, onClick: () -> Unit, onDelete: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val cardBgColor by animateColorAsState(
        targetValue = if (isHovered) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        label = "dockHover"
    )

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp),
        interactionSource = interactionSource,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = cardBgColor
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .padding(8.dp)
                    .fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Spacer(modifier = Modifier.height(20.dp))

                AppIcon(app = app)
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = app.label,
                    modifier = Modifier.basicMarquee(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    color = Color.LightGray,
                )
            }

            IconButton(
                onClick = onDelete,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(20.dp)
                    .background(
                        MaterialTheme.colorScheme.errorContainer,
                        RoundedCornerShape(6.dp)
                    )
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Remove",
                    modifier = Modifier
                        .size(16.dp)
                        .offset(x = (-3).dp, y = 1.dp),
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

@Composable
fun AddSlot(onAdd: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val cardBgColor by animateColorAsState(
        targetValue = if (isHovered) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        label = "addHover"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .hoverable(interactionSource)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                onClick = onAdd
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = cardBgColor
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(8.dp)
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = "Add",
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
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(8.dp)
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (app != null) {
                Spacer(modifier = Modifier.height(20.dp))
                AppIcon(app = app)
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = app.label,
                    modifier = Modifier.basicMarquee(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    color = Color.LightGray,
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(84.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(15.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "Add",
                        modifier = Modifier.size(54.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "No App Mgr",
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
fun AppIcon(app: AppInfo, size: androidx.compose.ui.unit.Dp = 84.dp) {
    val bitmap = remember(app.packageName) { app.icon?.toBitmap()?.asImageBitmap() }
    val cornerRadius = size * (15f / 84f)
    val iconSize = size * (54f / 84f)

    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(cornerRadius))
        )
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(cornerRadius)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                modifier = Modifier.size(iconSize),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPicker(
    onDismiss: () -> Unit,
    excludedPackages: Set<String> = emptySet(),
    onAppSelected: (AppInfo) -> Unit
) {
    val context = LocalContext.current
    val apps = remember {
        AppManager.getInstalledApps(context).filter { it.packageName !in excludedPackages }
    }
    var searchQuery by remember { mutableStateOf("") }
    val filteredApps = remember(searchQuery, apps) {
        if (searchQuery.isBlank()) apps else apps.filter {
            it.label.contains(
                searchQuery,
                ignoreCase = true
            )
        }
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
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                placeholder = { Text("Search apps...") },
                shape = RoundedCornerShape(12.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                )
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                items(filteredApps) { app ->
                    val itemInteractionSource = remember { MutableInteractionSource() }
                    val isItemHovered by itemInteractionSource.collectIsHoveredAsState()
                    val itemBgColor by animateColorAsState(
                        targetValue = if (isItemHovered) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f) else Color.Transparent,
                        label = "itemHover"
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(itemBgColor)
                            .hoverable(itemInteractionSource)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = ripple(),
                                onClick = { onAppSelected(app) }
                            )
                            .padding(horizontal = 24.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AppIcon(app = app, size = 48.dp)
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = app.label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White,
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 800, heightDp = 480)
@Composable
fun DefaultPreview() {
    PicoDockShortcutTheme {
        MainScreen()
    }
}
