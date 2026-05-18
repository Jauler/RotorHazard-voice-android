package com.rhvoice.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rhvoice.BuildConfig
import com.rhvoice.RhVoiceApp
import com.rhvoice.service.ConnectionStatus
import com.rhvoice.vm.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onOpenSettings: () -> Unit,
    vm: MainViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showBatteryNudge by remember { mutableStateOf(false) }

    val onStart: () -> Unit = {
        val repo = RhVoiceApp.get().settingsRepository
        if (!repo.batteryNudgeShown && !isIgnoringBatteryOptimizations(context)) {
            showBatteryNudge = true
        } else {
            vm.start()
        }
    }

    if (showBatteryNudge) {
        AlertDialog(
            onDismissRequest = { /* must pick one of the buttons */ },
            title = { Text("Keep the connection alive?") },
            text = {
                Text(
                    "Android may pause RH-Voice after the screen has been off for a few minutes. " +
                    "To make sure race events keep being announced, disable battery optimization " +
                    "for this app. You can change this later in Settings."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    RhVoiceApp.get().settingsRepository.batteryNudgeShown = true
                    showBatteryNudge = false
                    context.startActivity(
                        Intent(
                            AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${context.packageName}"),
                        )
                    )
                    vm.start()
                }) { Text("Disable optimization") }
            },
            dismissButton = {
                TextButton(onClick = {
                    RhVoiceApp.get().settingsRepository.batteryNudgeShown = true
                    showBatteryNudge = false
                    vm.start()
                }) { Text("Not now") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("RH-Voice ${BuildConfig.VERSION_NAME}") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Status", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                text = state.label(),
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(Modifier.height(32.dp))
            val canStart = state is ConnectionStatus.Stopped || state is ConnectionStatus.Error
            val canStop = state is ConnectionStatus.Connected || state is ConnectionStatus.Connecting
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(onClick = onStart, enabled = canStart) { Text("Start") }
                Button(onClick = vm::stop, enabled = canStop) { Text("Stop") }
            }
        }
    }
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

private fun ConnectionStatus.label(): String = when (this) {
    ConnectionStatus.Stopped -> "Stopped"
    ConnectionStatus.Connecting -> "Connecting…"
    ConnectionStatus.Connected -> "Connected"
    is ConnectionStatus.Error -> "Error: $message"
}
