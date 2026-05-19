package com.rhvoice.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rhvoice.tts.TtsPreviewer
import com.rhvoice.vm.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()

    val previewer = remember { TtsPreviewer(context) }
    DisposableEffect(Unit) { onDispose { previewer.shutdown() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionHeader("Connection")

            OutlinedTextField(
                value = settings.url,
                onValueChange = vm::setUrl,
                label = { Text("URL") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = settings.username,
                onValueChange = vm::setUsername,
                label = { Text("Username") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            var passwordVisible by remember { mutableStateOf(false) }
            OutlinedTextField(
                value = settings.password,
                onValueChange = vm::setPassword,
                label = { Text("Password") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        val icon = if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility
                        Icon(icon, contentDescription = if (passwordVisible) "Hide password" else "Show password")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            SectionHeader("Text-to-Speech")

            // Language
            val languages = remember(previewer) { previewer.availableLanguages() }
            LanguageDropdown(
                current = settings.ttsLocaleTag,
                languages = languages.map { it.toLanguageTag() }.sorted(),
                onSelect = vm::setLocale,
            )

            // Voice (filtered to current locale if set)
            val voices = remember(previewer, settings.ttsLocaleTag) {
                previewer.availableVoices().let { all ->
                    val tag = settings.ttsLocaleTag
                    if (tag.isNullOrBlank()) all
                    else all.filter { it.locale.toLanguageTag() == tag }
                }
            }
            VoiceDropdown(
                current = settings.ttsVoiceName,
                voices = voices.map { it.name }.sorted(),
                onSelect = vm::setVoice,
            )

            FloatSlider("Pitch", settings.ttsPitch, 0.5f..2.0f, vm::setPitch)
            FloatSlider("Speech rate", settings.ttsRate, 0.5f..2.0f, vm::setRate)
            FloatSlider("Volume", settings.ttsVolume, 0.0f..1.0f, vm::setVolume)
            FloatSlider("Pan (L↔R)", settings.ttsPan, -1.0f..1.0f, vm::setPan)

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Duck background music")
                    Text(
                        text = "Lower other audio (e.g. music) while announcements play.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = settings.duckMusic, onCheckedChange = vm::setDuckMusic)
            }

            Button(
                onClick = {
                    previewer.speak(
                        text = "RH-Voice test announcement.",
                        voiceName = settings.ttsVoiceName,
                        localeTag = settings.ttsLocaleTag,
                        pitch = settings.ttsPitch,
                        rate = settings.ttsRate,
                        volume = settings.ttsVolume,
                        pan = settings.ttsPan,
                        duckMusic = settings.duckMusic,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Speak sample") }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            SectionHeader("Battery")
            BatteryOptimizationCard()
        }
    }
}

@Composable
private fun BatteryOptimizationCard() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var ignoring by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }

    // Re-check whenever we return from the system settings screen.
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                ignoring = isIgnoringBatteryOptimizations(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = if (ignoring)
                "Battery optimization is disabled for RH-Voice. The connection will keep running with the screen off."
            else
                "Android may pause the connection after the screen has been off for a few minutes. " +
                "Disable battery optimization so race events keep being announced.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(
            onClick = {
                val intent = if (!ignoring) {
                    Intent(
                        AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${context.packageName}"),
                    )
                } else {
                    Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                }
                context.startActivity(intent)
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (ignoring) "Manage battery optimization" else "Disable battery optimization")
        }
    }
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun FloatSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column {
        Text(text = "$label: ${"%.2f".format(value)}")
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageDropdown(
    current: String?,
    languages: List<String>,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = current ?: "(default)",
            onValueChange = {},
            readOnly = true,
            label = { Text("Language") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable, true)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("(default)") },
                onClick = { onSelect(null); expanded = false },
            )
            languages.forEach { tag ->
                DropdownMenuItem(
                    text = { Text(tag) },
                    onClick = { onSelect(tag); expanded = false },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceDropdown(
    current: String?,
    voices: List<String>,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = current ?: "(default)",
            onValueChange = {},
            readOnly = true,
            label = { Text("Voice") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable, true)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("(default)") },
                onClick = { onSelect(null); expanded = false },
            )
            voices.forEach { name ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = { onSelect(name); expanded = false },
                )
            }
        }
    }
}
