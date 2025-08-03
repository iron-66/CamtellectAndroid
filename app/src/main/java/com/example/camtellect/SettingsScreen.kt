package com.example.camtellect

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment

@Composable
fun SettingsScreen(
    currentIp: String,
    allowBackground: Boolean,
    onIpChange: (String) -> Unit,
    onAllowBackgroundChange: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Text(text = "Settings", style = MaterialTheme.typography.headlineMedium)

        OutlinedTextField(
            value = currentIp,
            onValueChange = onIpChange,
            label = { Text("Wireless Camera IP") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = allowBackground,
                onCheckedChange = onAllowBackgroundChange
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Allow the application to run in the background")
        }

        Button(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Back")
        }
    }
}
