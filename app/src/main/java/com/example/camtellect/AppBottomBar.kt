package com.example.camtellect.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun AppBottomBar(
    connected: Boolean,
    isConnecting: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSelectCamera: (String) -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var cameraMenuExpanded by remember { mutableStateOf(false) }

    BottomAppBar(modifier = modifier) {
        // LEFT: camera icon with dropdown
        Box {
            IconButton(onClick = { cameraMenuExpanded = true }) {
                Icon(Icons.Filled.PhotoCamera, contentDescription = "Camera")
            }
            DropdownMenu(
                expanded = cameraMenuExpanded,
                onDismissRequest = { cameraMenuExpanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Back camera") },
                    onClick = {
                        cameraMenuExpanded = false
                        onSelectCamera("back")
                    }
                )
                DropdownMenuItem(
                    text = { Text("Front camera") },
                    onClick = {
                        cameraMenuExpanded = false
                        onSelectCamera("front")
                    }
                )
                DropdownMenuItem(
                    text = { Text("Wireless (coming soon)") },
                    onClick = { /* no-op */ },
                    enabled = false // disabled item
                )
            }
        }

        // CENTER: Connect/Disconnect (with loader)
        Box(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            contentAlignment = Alignment.Center
        ) {
            when {
                isConnecting -> {
                    Button(onClick = {}, enabled = false) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(ButtonDefaults.IconSize),
                                strokeWidth = 2.dp
                            )
                            Text("Connecting…")
                        }
                    }
                }
                !connected -> Button(onClick = onConnect) { Text("Connect") }
                else -> Button(onClick = onDisconnect) { Text("Disconnect") }
            }
        }

        // RIGHT: settings icon
        IconButton(onClick = onSettingsClick) {
            Icon(Icons.Filled.Settings, contentDescription = "Settings")
        }
    }
}
