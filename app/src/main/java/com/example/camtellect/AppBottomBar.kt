package com.example.camtellect.ui

import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun AppBottomBar(
    connected: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BottomAppBar(modifier = modifier) {
        if (!connected) {
            Button(onClick = onConnect) { Text("Connect") }
        } else {
            Button(onClick = onDisconnect) { Text("Disconnect") }
        }
    }
}
