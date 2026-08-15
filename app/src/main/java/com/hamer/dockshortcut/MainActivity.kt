package com.hamer.dockshortcut

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hamer.dockshortcut.components.*
import com.hamer.dockshortcut.drawer.*
import com.hamer.dockshortcut.ui.theme.PicoDockShortcutTheme
import com.hamer.dockshortcut.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File

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
    var showBgSettings by remember { mutableStateOf(false) }
    var showLanguageSelector by remember { mutableStateOf(false) }
    var showImagePicker by remember { mutableStateOf(false) }
    var imagePickerTarget by remember { mutableStateOf<PickerTarget>(PickerTarget.None) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var pickingIconIndex by remember { mutableStateOf<Int?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.any { it }) {
            showImagePicker = true
        } else {
            Toast.makeText(context, context.getString(R.string.toast_permission_denied), Toast.LENGTH_SHORT).show()
        }
    }

    fun requestImagePermission() {
        val permissions = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val allGranted = permissions.all {
            androidx.core.content.ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            showImagePicker = true
        } else {
            permissionLauncher.launch(permissions)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.loadApps(context)
        viewModel.checkStatus()
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(24.dp)),
            color = colorResource(id = R.color.main_bg)
        ) {
            val showStatusOverlay = !viewModel.hasRoot || !viewModel.isModuleActive || !viewModel.isTargetHooked

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                Header(
                    viewModel,
                    context,
                    onLanguageClick = { showLanguageSelector = true },
                    onBgClick = { showBgSettings = true }
                )
                Spacer(modifier = Modifier.height(24.dp))
                // Set grid weight(1f): measure bottom background area first, grid takes the remaining height
                // (Previously LazyVerticalGrid had no weight, it would consume all available height -> bottom buttons were pushed off screen)
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
                        imagePickerTarget = PickerTarget.Icon(index)
                        requestImagePermission()
                    }
                )
            }

            if (showBgSettings || showPicker || showLanguageSelector || showStatusOverlay || showImagePicker) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.32f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {}
                        ) // Block interactions
                )
            }

            if (showStatusOverlay) {
                WarningOverlay(viewModel, context)
            }
        }
    }

    if (showBgSettings) {
        DockBgDrawer(
            viewModel = viewModel,
            onDismiss = { showBgSettings = false },
            onPickImage = {
                imagePickerTarget = PickerTarget.Background
                requestImagePermission()
            }
        )
    }

    if (showPicker) {
        val excluded = viewModel.selectedApps.map { it.packageName }.toSet() + "com.pvr.appmanager"
        AppPicker(
            viewModel = viewModel,
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
        LanguagePicker(onDismiss = { showLanguageSelector = false })
    }

    if (showImagePicker) {
        ImageFilePicker(
            onDismiss = {
                showImagePicker = false
                imagePickerTarget = PickerTarget.None
            },
            onImageSelected = { uri ->
                when (val target = imagePickerTarget) {
                    is PickerTarget.Icon -> {
                        val app = viewModel.selectedApps[target.index]
                        viewModel.saveCustomIcon(context, uri, app.packageName)
                    }
                    is PickerTarget.Background -> {
                        // This will be handled by DockBgDrawer if we pass the state
                        // Or we can use a SharedFlow/Event in ViewModel
                        viewModel.onImagePicked(uri)
                    }
                    else -> {}
                }
                showImagePicker = false
                imagePickerTarget = PickerTarget.None
            }
        )
    }
}

sealed class PickerTarget {
    object None : PickerTarget()
    data class Icon(val index: Int) : PickerTarget()
    object Background : PickerTarget()
}

@Composable
private fun Header(
    viewModel: MainViewModel,
    context: Context,
    onLanguageClick: () -> Unit,
    onBgClick: () -> Unit
) {
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
            val bgInteractionSource = remember { MutableInteractionSource() }
            val langInteractionSource = remember { MutableInteractionSource() }

            Surface(
                onClick = onBgClick,
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(12.dp),
                color = colorResource(R.color.card_bg),
                interactionSource = bgInteractionSource
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Wallpaper,
                        contentDescription = stringResource(R.string.dock_bg_title),
                        tint = Color.LightGray
                    )
                }
            }

            Surface(
                onClick = onLanguageClick,
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(12.dp),
                color = colorResource(R.color.card_bg),
                interactionSource = langInteractionSource
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Language,
                        contentDescription = stringResource(R.string.select_language),
                        tint = Color.LightGray
                    )
                }
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
                colorResource(id = R.color.colorPrimary),
                viewModel.isApplying || !viewModel.isModified,
                showLoading = viewModel.isApplying
            ) {
                viewModel.applyChanges(context, false)
            }
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

    Box(modifier = modifier) {
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
                    key = { _, app -> app.packageName }
                ) { index, app ->
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
                                        val col = (touchPosition.x / (slotSize.x + spacing)).toInt()
                                            .coerceIn(0, 5)
                                        val row = (touchPosition.y / (slotSize.y + spacing)).toInt()
                                            .coerceIn(0, 1)

                                        val targetIdx =
                                            (row * 6 + col).coerceIn(0, viewModel.selectedApps.size - 1)

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
                    item {
                        Box {
                            AddSlot(onClick = onAddClick)
                        }
                    }
                }

                item {
                    val context = LocalContext.current
                    val appMgrLabel = stringResource(R.string.app_manager_label)
                    val appMgr = remember(appMgrLabel) {
                        AppManager.getAppInfo(context, "com.pvr.appmanager")?.copy(
                            label = appMgrLabel,
                            className = "com.pvr.appmanager.AllAppActivity"
                        )
                    }
                    Box {
                        FixedSlot(appMgr)
                    }
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
            containerColor = colorResource(id = R.color.card_bg)
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
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(40.dp)
                    .background(
                        MaterialTheme.colorScheme.tertiaryContainer,
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
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .size(40.dp)
                    .background(
                        MaterialTheme.colorScheme.secondaryContainer,
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
            containerColor =  colorResource(id = R.color.card_bg)
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
            containerColor = colorResource(id = R.color.card_bg)
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
private fun WarningOverlay(viewModel: MainViewModel, context: Context) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.8f),
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFF333333),
            contentColor = Color.White,
            tonalElevation = 8.dp
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                val title = if (!viewModel.hasRoot) {
                    stringResource(R.string.dialog_root_title)
                } else {
                    stringResource(R.string.dialog_warning_title)
                }

                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(16.dp))

                if (!viewModel.hasRoot) {
                    Text(stringResource(R.string.dialog_root_text))
                } else {
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
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { (context as? android.app.Activity)?.finish() }) {
                        Text(stringResource(R.string.dialog_exit))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    if (!viewModel.hasRoot) {
                        Button(
                            onClick = { viewModel.checkStatus() },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(stringResource(R.string.dialog_retry))
                        }
                    } else {
                        Button(
                            onClick = { viewModel.restartAndRetry(context) },
                            enabled = !viewModel.isRetrying,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (viewModel.isRetrying) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = Color.White
                                )
                            } else {
                                Text(stringResource(R.string.dialog_restart_retry))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 800, heightDp = 480)
@Composable
fun DefaultPreview() {
    PicoDockShortcutTheme { MainScreen() }
}
