package com.hamer.dockshortcut.utils

import android.content.Context
import android.util.Log
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView

/*Native Pico OSUIHelper via com.bytedance.osui.utils.OSUIHelper*/
fun triggerHaptic(
    context: Context,
    view: View? = null,
    isHover: Boolean = false,
    deviceId: Int = 0
) {
    val amplitude = if (isHover) 120f else 255f
    val frequency = if (isHover) 177f else 100f
    val duration = if (isHover) 10f else 35f

    val nativeDeviceId: Int = when (deviceId) {
        100001, 1 -> 1
        100002, 2 -> 2
        else -> 2
    }

    try {
        if (com.bytedance.osui.utils.OSUIHelper.isLoaded) {
            val res = com.bytedance.osui.utils.OSUIHelper.a(nativeDeviceId, amplitude, frequency, duration)
            Log.d("PicoHaptic", "OSUIHelper.a result for nativeDeviceId $nativeDeviceId (devId $deviceId): $res")
        } else {
            Log.w("PicoHaptic", "OSUIHelper is not loaded")
        }
    } catch (t: Throwable) {
        Log.e("PicoHaptic", "Error calling OSUIHelper.a", t)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.picoHapticHover(context: Context, view: View? = null): Modifier = this.pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            val motionEvent = event.motionEvent
            val deviceId = motionEvent?.deviceId ?: 0
            if (event.type == PointerEventType.Enter) {
                triggerHaptic(context, view, isHover = true, deviceId = deviceId)
            } else if (event.type == PointerEventType.Press) {
                triggerHaptic(context, view, isHover = false, deviceId = deviceId)
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Modifier.picoHapticHover(): Modifier {
    val context = LocalContext.current
    val view = LocalView.current
    return this.picoHapticHover(context, view)
}
