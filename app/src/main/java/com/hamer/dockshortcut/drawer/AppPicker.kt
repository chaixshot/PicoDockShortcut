package com.hamer.dockshortcut.drawer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.hamer.dockshortcut.AppInfo
import com.hamer.dockshortcut.AppManager
import com.hamer.dockshortcut.FIT_CENTER_PACKAGE
import com.hamer.dockshortcut.MainViewModel
import com.hamer.dockshortcut.R
import com.hamer.dockshortcut.components.AppIcon
import com.hamer.dockshortcut.ui.theme.PicoDockShortcutTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPicker(
    viewModel: MainViewModel,
    onDismiss: () -> Unit,
    excludedPackages: Set<String>,
    onAppSelected: (AppInfo) -> Unit
) {
    val context = LocalContext.current
    val apps by produceState(emptyList<AppInfo>()) {
        value = withContext(Dispatchers.IO) {
            AppManager.getInstalledApps(context).filter {
                it.packageName !in excludedPackages && it.packageName != FIT_CENTER_PACKAGE
            }
        }
    }

    val fitCenterInfo = remember(context) {
        if (AppManager.isPackageInstalled(context, FIT_CENTER_PACKAGE)) {
            AppManager.getAppInfo(context, FIT_CENTER_PACKAGE)
        } else null
    }

    AppPickerContent(
        apps = apps,
        filterUser = viewModel.filterUser,
        filterSystem = viewModel.filterSystem,
        onToggleFilterUser = { viewModel.toggleFilterUser(context) },
        onToggleFilterSystem = { viewModel.toggleFilterSystem(context) },
        onDismiss = onDismiss,
        onAppSelected = onAppSelected,
        excludedPackages = excludedPackages,
        fitCenterInfo = fitCenterInfo
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppPickerContent(
    apps: List<AppInfo>,
    filterUser: Boolean,
    filterSystem: Boolean,
    onToggleFilterUser: () -> Unit,
    onToggleFilterSystem: () -> Unit,
    onDismiss: () -> Unit,
    onAppSelected: (AppInfo) -> Unit,
    excludedPackages: Set<String>,
    fitCenterInfo: AppInfo? = null
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        scrimColor = Color.Transparent,
        containerColor = colorResource(R.color.main_bg),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        AppPickerSheetContent(
            apps = apps,
            filterUser = filterUser,
            filterSystem = filterSystem,
            onToggleFilterUser = onToggleFilterUser,
            onToggleFilterSystem = onToggleFilterSystem,
            onAppSelected = onAppSelected,
            excludedPackages = excludedPackages,
            fitCenterInfo = fitCenterInfo
        )
    }
}

@Composable
private fun AppPickerSheetContent(
    apps: List<AppInfo>,
    filterUser: Boolean,
    filterSystem: Boolean,
    onToggleFilterUser: () -> Unit,
    onToggleFilterSystem: () -> Unit,
    onAppSelected: (AppInfo) -> Unit,
    excludedPackages: Set<String>,
    fitCenterInfo: AppInfo? = null
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, apps, filterUser, filterSystem) {
        apps.filter {
            it.label.contains(query, true) &&
                    ((filterUser && !it.isSystem) || (filterSystem && it.isSystem))
        }
    }

    Column(modifier = Modifier.fillMaxHeight(0.9f)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextField(
                value = query, onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.search_placeholder)) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        val interactionSource = remember { MutableInteractionSource() }
                        val isHovered by interactionSource.collectIsHoveredAsState()
                        IconButton(
                            onClick = {
                                query = ""
                            },
                            interactionSource = interactionSource
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Clear",
                                tint = if (isHovered) Color.White else Color.Gray
                            )
                        }
                    }
                },
                shape = RoundedCornerShape(12.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = colorResource(R.color.content_bg),
                    unfocusedContainerColor = colorResource(R.color.card_bg),
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                )
            )

            FilterToggleButton(
                text = "User",
                isActive = filterUser,
                onClick = onToggleFilterUser
            )

            FilterToggleButton(
                text = "System",
                isActive = filterSystem,
                onClick = onToggleFilterSystem
            )
        }

        if (apps.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                if (fitCenterInfo != null &&
                    fitCenterInfo.label.contains(query, true) &&
                    excludedPackages.none { it == FIT_CENTER_PACKAGE }
                ) {
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

@Composable
private fun FilterToggleButton(
    text: String,
    isActive: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = {
            onClick()
        },
        shape = RoundedCornerShape(12.dp),
        color = if (isActive) colorResource(R.color.colorPrimary) else colorResource(R.color.card_bg),
        contentColor = if (isActive) Color.Black else Color.White
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(text = text, style = MaterialTheme.typography.labelLarge)
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
                if (isHovered) colorResource(R.color.content_bg) else Color.Transparent
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
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = app.packageName,
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray.copy(alpha = 0.6f),
            modifier = Modifier.align(Alignment.Bottom)
        )
    }
}

@Preview(showBackground = true, widthDp = 800, heightDp = 480)
@Composable
fun AppPickerPreview() {
    val sampleApps = listOf(
        AppInfo("com.android.settings", "Settings", "Settings", isSystem = true),
        AppInfo("com.google.android.youtube", "YouTube", "YouTube", isSystem = false),
        AppInfo("com.example.app", "Example", "My App", isSystem = false),
        AppInfo("com.android.chrome", "Chrome", "Chrome", isSystem = true)
    )

    PicoDockShortcutTheme {
        Surface(color = colorResource(R.color.main_bg)) {
            AppPickerSheetContent(
                apps = sampleApps,
                filterUser = true,
                filterSystem = true,
                onToggleFilterUser = {},
                onToggleFilterSystem = {},
                onAppSelected = {},
                excludedPackages = emptySet(),
                fitCenterInfo = AppInfo(FIT_CENTER_PACKAGE, null, "Fit Center", fitCenter = true)
            )
        }
    }
}
