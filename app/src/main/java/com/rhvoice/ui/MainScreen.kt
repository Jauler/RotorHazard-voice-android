package com.rhvoice.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rhvoice.BuildConfig
import com.rhvoice.service.ConnectionStatus
import com.rhvoice.vm.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onOpenSettings: () -> Unit,
    vm: MainViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

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
                Button(onClick = vm::start, enabled = canStart) { Text("Start") }
                Button(onClick = vm::stop, enabled = canStop) { Text("Stop") }
            }
        }
    }
}

private fun ConnectionStatus.label(): String = when (this) {
    ConnectionStatus.Stopped -> "Stopped"
    ConnectionStatus.Connecting -> "Connecting…"
    ConnectionStatus.Connected -> "Connected"
    is ConnectionStatus.Error -> "Error: $message"
}
