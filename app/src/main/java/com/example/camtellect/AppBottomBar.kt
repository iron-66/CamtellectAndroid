package com.example.camtellect.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun AppBottomBar(
    connected: Boolean,
    isConnecting: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BottomAppBar(modifier = modifier) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            when {
                isConnecting -> {
                    Button(
                        onClick = { /* disabled while connecting */ },
                        enabled = false
                    ) {
                        // Компактный лоадер + текст; размер кнопки не «прыгает»
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
                !connected -> {
                    Button(onClick = onConnect) { Text("Connect") }
                }
                else -> {
                    Button(onClick = onDisconnect) { Text("Disconnect") }
                }
            }
        }
    }
}
