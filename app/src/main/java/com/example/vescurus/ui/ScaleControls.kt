package com.example.vescurus.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import java.util.Locale

internal fun scaleGrams(grams: Double) = String.format(Locale.US, "%.1f g", grams)

@Composable
internal fun MonitorScale(scale: CookScale, onMeasured: (Double) -> Unit = {}) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val onResult by rememberUpdatedState(onMeasured)
    val address = scale.address
    LaunchedEffect(scale, lifecycle, address) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            scale.monitor(address) { onResult(it) }
        }
    }
}

@Composable
internal fun ScaleSettings(scale: CookScale) {
    var input by rememberSaveable { mutableStateOf(scale.address) }
    var notice by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    MonitorScale(scale)
    SessionCard("Kitchen scale") {
        OutlinedTextField(
            input, { input = it.take(15) }, label = { Text("Scale IP address") },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                notice = if (scale.connect(input)) "" else "Enter the local IPv4 address printed by the scale."
            }) { Text("Connect") }
            if (scale.address.isNotEmpty()) TextButton(onClick = { scale.disconnect(); notice = "" }) { Text("Disconnect") }
        }
        if (scale.address.isNotEmpty()) {
            Text(scale.status)
            scale.reading?.takeIf { it.status == "ready" }?.let { Text(scaleGrams(it.grams)) }
            OutlinedButton(
                enabled = !scale.taring && scale.reading?.status in setOf("ready", "tare_required"),
                onClick = { scope.launch { notice = scale.tare() } }
            ) { Text("Tare empty holder") }
            if (scale.reading?.calibrated == false) {
                Text("Calibrate with a known mass over USB after mounting the scale.")
            }
        }
        if (notice.isNotBlank()) Text(notice)
    }
}
