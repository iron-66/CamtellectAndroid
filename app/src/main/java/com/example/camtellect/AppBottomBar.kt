package com.example.camtellect.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.surfaceColorAtElevation

@Composable
fun AppBottomBar(
    connected: Boolean,
    isConnecting: Boolean,
    wirelessEnabled: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSelectCamera: (String) -> Unit,  // "back" | "front" | "wireless"
    onSettingsClick: () -> Unit,
    cameraMenuExpanded: Boolean,
    onCameraMenuExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    BottomAppBar(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp),
        tonalElevation = 6.dp,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Box {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            ) {
                IconButton(onClick = { onCameraMenuExpandedChange(true) }) {
                    Icon(Icons.Outlined.PhotoCamera, contentDescription = "Select camera")
                }
            }
            DropdownMenu(
                expanded = cameraMenuExpanded,
                onDismissRequest = { onCameraMenuExpandedChange(false) }
            ) {
                DropdownMenuItem(
                    text = { Text("Back camera") },
                    onClick = {
                        onCameraMenuExpandedChange(false)
                        onSelectCamera("back")
                    }
                )
                DropdownMenuItem(
                    text = { Text("Front camera") },
                    onClick = {
                        onCameraMenuExpandedChange(false)
                        onSelectCamera("front")
                    }
                )
                DropdownMenuItem(
                    text = { Text("Wireless camera") },
                    onClick = {
                        onCameraMenuExpandedChange(false)
                        onSelectCamera("wireless")
                    },
                    enabled = wirelessEnabled
                )
            }
        }

        Spacer(Modifier.width(16.dp))

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center
        ) {
            when {
                isConnecting -> {
                    FilledTonalButton(
                        onClick = {},
                        enabled = false,
                        shape = RoundedCornerShape(32.dp)
                    ) {
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

                !connected -> FilledTonalButton(
                    onClick = onConnect,
                    shape = RoundedCornerShape(32.dp)
                ) { Text("Connect") }

                else -> FilledTonalButton(
                    onClick = onDisconnect,
                    shape = RoundedCornerShape(32.dp)
                ) { Text("Disconnect") }
            }
        }

        Spacer(Modifier.width(16.dp))

        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        ) {
            IconButton(onClick = onSettingsClick) {
                Icon(Icons.Outlined.Settings, contentDescription = "Settings")
            }
        }
    }
}
