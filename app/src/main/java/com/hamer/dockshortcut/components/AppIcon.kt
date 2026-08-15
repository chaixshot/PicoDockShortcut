package com.hamer.dockshortcut.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.hamer.dockshortcut.AppInfo
import com.hamer.dockshortcut.AppManager
import com.hamer.dockshortcut.R
import com.hamer.dockshortcut.isFitCenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun AppIcon(app: AppInfo, size: androidx.compose.ui.unit.Dp = 84.dp) {
    val context = LocalContext.current
    val iconBitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(
        null,
        app.packageName,
        app.fitCenter,
        app.iconUrl
    ) {
        value = withContext(Dispatchers.IO) {
            val customFile =
                File(context.filesDir.parentFile, "Image/Custom/custom_icon_${app.packageName}.png")
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
                    colorResource(id = R.color.card_bg),
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
