package com.example.camtellect

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier

@Composable
fun AppBottomBar(
    cameraOptions: List<CameraOption>,
    selectedCamera: String,
    onCameraSelect: (String) -> Unit,
    isRecording: Boolean,
    onRecordToggle: () -> Unit,
    onSettingsClick: () -> Unit,
    ipAddress: String
) {
    var cameraMenuOpen by remember { mutableStateOf(false) }
    BottomAppBar {
        Box {
            IconButton(onClick = { cameraMenuOpen = true }) {
                Icon(Icons.Default.PhotoCamera, contentDescription = "Switch Camera")
            }
            DropdownMenu(
                expanded = cameraMenuOpen,
                onDismissRequest = { cameraMenuOpen = false }
            ) {
                cameraOptions.forEach { option ->
                    val isWireless = option.id == "wireless"
                    val enabled = !isWireless || ipAddress.isNotEmpty()
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        enabled = enabled,
                        onClick = {
                            if (enabled) {
                                onCameraSelect(option.id)
                                cameraMenuOpen = false
                            }
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(onClick = onRecordToggle) {
            Text(if (!isRecording) "🎤 Record" else "■ Stop")
        }

        Spacer(modifier = Modifier.weight(1f))

        IconButton(onClick = onSettingsClick) {
            Icon(Icons.Default.Settings, contentDescription = "Settings")
        }
    }
}
