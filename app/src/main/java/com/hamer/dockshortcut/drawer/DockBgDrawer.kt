package com.hamer.dockshortcut.drawer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.hamer.dockshortcut.MainViewModel
import com.hamer.dockshortcut.R
import com.hamer.dockshortcut.components.ActionButton
import com.hamer.dockshortcut.components.CropDialog
import com.hamer.dockshortcut.ui.theme.PicoDockShortcutTheme
import com.hamer.dockshortcut.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DockBgDrawer(
    viewModel: MainViewModel,
    onDismiss: () -> Unit,
    onPickImage: () -> Unit
) {
    val context = LocalContext.current
    var bgInfo by remember { mutableStateOf(readBgInfo(context)) }
    var lastUpdate by remember { mutableLongStateOf(0L) }
    var cropUri by remember { mutableStateOf<Uri?>(null) }
    var editCurrentBg by remember { mutableStateOf(false) }

    // Observe global image picker results
    LaunchedEffect(viewModel.pickedImageUri) {
        if (viewModel.pickedImageUri != null) {
            cropUri = viewModel.pickedImageUri
            viewModel.clearPickedImage()
        }
    }

    val currentBgBitmap by produceState<Bitmap?>(initialValue = null, bgInfo, lastUpdate, viewModel.bgPendingRestore, viewModel.bgPendingBitmap) {
        value = if (viewModel.bgPendingBitmap != null) {
            viewModel.bgPendingBitmap
        } else {
            withContext(Dispatchers.IO) {
                if (viewModel.bgPendingRestore) return@withContext null
                try {
                    val f = File(context.filesDir.parentFile, "dock_bg.png")
                    if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

    val barRatio = remember(viewModel.selectedApps.size, bgInfo) {
        dockBarAspect(context, viewModel.selectedApps.size)
    }
    val maxRatio = remember { DOCK_MAX_ASPECT }

    if (cropUri != null || (editCurrentBg && currentBgBitmap != null)) {
        CropDialog(
            uri = cropUri,
            bitmap = if (editCurrentBg) currentBgBitmap else null,
            aspect = maxRatio,
            visibleAspect = barRatio,
            appCount = viewModel.selectedApps.size,
            onDismiss = {
                cropUri = null
                editCurrentBg = false
            },
            onConfirm = { cropped ->
                bgInfo = "Pending application..."
                viewModel.bgPendingBitmap = cropped
                viewModel.bgPendingRestore = false
                viewModel.bgModified = true
                cropUri = null
                editCurrentBg = false
                Toast.makeText(context, "Background staged (click Apply to save)", Toast.LENGTH_SHORT).show()
            }
        )
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        scrimColor = Color.Transparent,
        containerColor = colorResource(id = R.color.content_bg),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        DockBgDrawerContent(
            bgInfo = bgInfo,
            currentBgBitmap = currentBgBitmap,
            selectedAppsCount = viewModel.selectedApps.size,
            bgModified = viewModel.bgModified,
            bgPendingRestore = viewModel.bgPendingRestore,
            onPickImage = onPickImage,
            onEditCurrentBg = { editCurrentBg = true },
            onRestore = {
                viewModel.bgPendingRestore = true
                viewModel.bgModified = false
                onDismiss()
            },
            onApply = {
                onDismiss()
            }
        )
    }
}

@Composable
private fun DockBgDrawerContent(
    bgInfo: String?,
    currentBgBitmap: Bitmap?,
    selectedAppsCount: Int,
    bgModified: Boolean,
    bgPendingRestore: Boolean,
    onPickImage: () -> Unit,
    onEditCurrentBg: () -> Unit,
    onRestore: () -> Unit,
    onApply: () -> Unit
) {
    val context = LocalContext.current
    val barRatio = remember(selectedAppsCount, bgInfo) {
        dockBarAspect(context, selectedAppsCount)
    }
    val maxRatio = remember { DOCK_MAX_ASPECT }

    Column(
        modifier = Modifier
            .fillMaxHeight(0.9f)
            .fillMaxWidth()
            .padding(horizontal = 32.dp)
    ) {
        Text(
            stringResource(R.string.dock_bg_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Spacer(modifier = Modifier.height(16.dp))

        if (!bgPendingRestore)
            currentBgBitmap?.let { bmp ->
                Box(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(714f / 47f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Black)
                    ) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "Current Background",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.FillHeight,
                            alignment = Alignment.CenterStart
                        )
                    }

                    if (!bgInfo.isNullOrBlank()) {
                        Surface(
                            onClick = { onEditCurrentBg() },
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .size(30.dp),
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Edit,
                                    contentDescription = "Edit Current",
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = colorResource(id = R.color.content_bg)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    if (bgInfo.isNullOrBlank())
                        stringResource(R.string.dock_bg_not_set)
                    else stringResource(R.string.dock_bg_current, bgInfo),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorResource(id = R.color.colorPrimary)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(
                        R.string.dock_bg_desc,
                        maxRatio,
                        selectedAppsCount,
                        (barRatio / maxRatio * 100).toInt(),
                        MAX_RECENT_APPS
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ActionButton(
                text = stringResource(R.string.action_restore),
                Icons.Default.SettingsBackupRestore,
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                disabled = (bgInfo.isNullOrBlank() && !bgModified) || bgPendingRestore,
                modifier = Modifier.weight(1f)
            ) {
                onRestore()
            }

            ActionButton(
                text = stringResource(R.string.action_choose_image),
                icon = Icons.Default.Image,
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                disabled = false,
                modifier = Modifier.weight(1f)
            ) { onPickImage() }

            ActionButton(
                text = stringResource(R.string.action_apply),
                icon = Icons.Default.Check,
                containerColor = colorResource(id = R.color.colorPrimary),
                disabled = bgInfo.isNullOrBlank() || !bgModified || bgPendingRestore,
                modifier = Modifier.weight(1f)
            ) {
                onApply()
            }
        }
    }
}

// Read background information from the module data directory (display filename + resolution if exists)
private fun readBgInfo(context: android.content.Context): String? {
    return try {
        val f = File(context.filesDir.parentFile, "dock_bg.png")
        if (!f.exists()) return null
        val bmp = BitmapFactory.decodeFile(f.absolutePath)
        if (bmp == null) context.getString(R.string.bg_info_cannot_decode)
        else context.getString(R.string.bg_info_resolution, bmp.width, bmp.height)
    } catch (e: Exception) {
        context.getString(R.string.bg_info_not_available)
    }
}

@Preview(showBackground = true)
@Composable
fun DockBgDrawerPreview() {
    PicoDockShortcutTheme {
        DockBgDrawerContent(
            bgInfo = "714x47",
            currentBgBitmap = null,
            selectedAppsCount = 8,
            bgModified = false,
            bgPendingRestore = false,
            onPickImage = {},
            onEditCurrentBg = {},
            onRestore = {},
            onApply = {}
        )
    }
}
