package com.hamer.dockshortcut.components

import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.hamer.dockshortcut.R
import com.hamer.dockshortcut.ui.theme.PicoDockShortcutTheme
import com.hamer.dockshortcut.utils.decodeScaled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun CropDialog(
    uri: Uri? = null,
    bitmap: Bitmap? = null,
    aspect: Float,
    visibleAspect: Float = aspect,
    appCount: Int = 0,
    onDismiss: () -> Unit,
    onConfirm: (Bitmap) -> Unit
) {
    val context = LocalContext.current
    val src = remember(uri, bitmap) {
        bitmap ?: if (uri != null) decodeScaled(context, uri, 3000) else null
    }

    if (src == null) {
        val toastDecodeFailed = stringResource(R.string.toast_decode_failed)
        LaunchedEffect(Unit) {
            Toast.makeText(
                context,
                toastDecodeFailed,
                Toast.LENGTH_LONG
            ).show()
            onDismiss()
        }
        return
    }

    val img = remember(src) { src.asImageBitmap() }
    val iw = src.width.toFloat()
    val ih = src.height.toFloat()

    // Zoom 1 = the largest area of the same aspect ratio that can be obtained within the image
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

    val colorPrimary = colorResource(id = R.color.colorPrimary)
    val card_bg = colorResource(id = R.color.card_bg)

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = colorResource(id = R.color.main_bg),
            modifier = Modifier.fillMaxWidth(0.9f)
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    stringResource(R.string.crop_dialog_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    stringResource(R.string.crop_dialog_info, aspect, src.width, src.height),
                    style = MaterialTheme.typography.bodySmall,
                    color = colorPrimary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    if (visibleAspect < aspect)
                        stringResource(R.string.crop_dialog_visible_hint, appCount)
                    else stringResource(R.string.crop_dialog_all_visible_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(modifier = Modifier.height(16.dp))

                // Cropping frame (fixed ratio), drawn directly according to src rectangle internally => what you see is what you get
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(aspect)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black)
                        .pointerInput(src, aspect) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                val currentCropW = maxCropW / zoom
                                val currentCropH = maxCropH / zoom
                                val kx = currentCropW / frameW.coerceAtLeast(1f)
                                val ky = currentCropH / frameH.coerceAtLeast(1f)
                                cx = (cx - drag.x * kx).coerceIn(currentCropW / 2f, iw - currentCropW / 2f)
                                cy = (cy - drag.y * ky).coerceIn(currentCropH / 2f, ih - currentCropH / 2f)
                            }
                        }
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        frameW = size.width
                        frameH = size.height
                        val sx = (cx - cropW / 2f).coerceAtLeast(0f)
                        val sy = (cy - cropH / 2f).coerceAtLeast(0f)
                        drawImage(
                            image = img,
                            srcOffset = IntOffset(
                                sx.toInt().coerceIn(0, src.width - 1),
                                sy.toInt().coerceIn(0, src.height - 1)
                            ),
                            srcSize = IntSize(
                                cropW.toInt().coerceIn(1, src.width - sx.toInt()),
                                cropH.toInt().coerceIn(1, src.height - sy.toInt())
                            ),
                            dstOffset = IntOffset.Zero,
                            dstSize = IntSize(
                                size.width.toInt(), size.height.toInt()
                            ),
                            filterQuality = FilterQuality.High
                        )
                        // Visible boundary for the current number of apps: darkened right side + a dividing line
                        if (visibleAspect < aspect) {
                            val vw = size.width * (visibleAspect / aspect)
                            drawRect(
                                color = Color.Black.copy(alpha = 0.55f),
                                topLeft = Offset(vw, 0f),
                                size = androidx.compose.ui.geometry.Size(
                                    size.width - vw,
                                    size.height
                                )
                            )
                            drawLine(
                                color = colorPrimary,
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
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = card_bg,
                            activeTrackColor = card_bg,
                            inactiveTrackColor = card_bg.copy(alpha = 0.24f),
                            activeTickColor = Color.Transparent,
                            inactiveTickColor = Color.Transparent
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("${"%.1f".format(zoom)}x", color = Color.LightGray)
                }
                Text(
                    stringResource(R.string.crop_dialog_instruction),
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
                        text = stringResource(R.string.action_cancel),
                        icon = Icons.Default.Close,
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        disabled = false
                    ) { onDismiss() }
                    ActionButton(
                        text = stringResource(R.string.action_use_region),
                        icon = Icons.Default.Check,
                        containerColor = colorResource(id = R.color.colorPrimary),
                        disabled = false
                    ) {
                        try {
                            val sx = (cx - cropW / 2f).toInt().coerceIn(0, src.width - 1)
                            val sy = (cy - cropH / 2f).toInt().coerceIn(0, src.height - 1)
                            val cw = cropW.toInt().coerceIn(1, src.width - sx)
                            val ch = cropH.toInt().coerceIn(1, src.height - sy)
                            var out = Bitmap.createBitmap(src, sx, sy, cw, ch)
                            // Output to twice the actual pixels of the Dock bar, saves memory
                            val targetH = 320
                            if (out.height > targetH) {
                                val targetW = (targetH * aspect).toInt().coerceAtLeast(1)
                                out = Bitmap.createScaledBitmap(
                                    out, targetW, targetH, true
                                )
                            }
                            onConfirm(out)
                        } catch (e: Exception) {
                            val msg = e.message ?: ""
                            Toast.makeText(context, "Crop failed: $msg", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun CropDialogPreview() {
    val bitmap = remember {
        Bitmap.createBitmap(500, 500, Bitmap.Config.ARGB_8888).apply {
            val canvas = android.graphics.Canvas(this)
            val paint = android.graphics.Paint()
            paint.color = android.graphics.Color.BLUE
            canvas.drawRect(0f, 0f, 500f, 500f, paint)
            paint.color = android.graphics.Color.WHITE
            paint.textSize = 40f
            canvas.drawText("Sample Image", 100f, 250f, paint)
        }
    }

    PicoDockShortcutTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            CropDialog(
                bitmap = bitmap,
                aspect = 1.5f,
                appCount = 4,
                onDismiss = {},
                onConfirm = {}
            )
        }
    }
}
