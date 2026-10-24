package com.hyntix.android.usbflasher.ui

import androidx.compose.foundation.clickable
import androidx.compose.ui.unit.dp

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import com.hyntix.android.usbflasher.R
import com.hyntix.android.usbflasher.util.ThemePreference
import kotlinx.coroutines.launch

@Composable
fun SettingsDialog(
    onDismiss: () -> Unit,
    onThemeChanged: (String) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val currentMode = ThemePreference.getThemeMode(context)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_title)) },
        text = {
            androidx.compose.foundation.layout.Column(
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
            ) {
                Text(stringResource(R.string.settings_theme_description), style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
                SettingsThemeOption(
                    label = stringResource(R.string.settings_theme_system),
                    selected = currentMode == ThemePreference.MODE_SYSTEM,
                    onClick = { ThemePreference.setThemeMode(context, ThemePreference.MODE_SYSTEM); onThemeChanged(ThemePreference.MODE_SYSTEM); (context as? android.app.Activity)?.recreate() }
                )
                SettingsThemeOption(
                    label = stringResource(R.string.settings_theme_light),
                    selected = currentMode == ThemePreference.MODE_LIGHT,
                    onClick = { ThemePreference.setThemeMode(context, ThemePreference.MODE_LIGHT); onThemeChanged(ThemePreference.MODE_LIGHT); (context as? android.app.Activity)?.recreate() }
                )
                SettingsThemeOption(
                    label = stringResource(R.string.settings_theme_dark),
                    selected = currentMode == ThemePreference.MODE_DARK,
                    onClick = { ThemePreference.setThemeMode(context, ThemePreference.MODE_DARK); onThemeChanged(ThemePreference.MODE_DARK); (context as? android.app.Activity)?.recreate() }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_ok))
            }
        }
    )
}

@Composable
private fun SettingsThemeOption(label: String, selected: Boolean, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        modifier = androidx.compose.ui.Modifier.clickable(onClick = onClick)
    ) {
        RadioButton(selected = selected, onClick = onClick)
        androidx.compose.material3.Text(text = label)
    }
}
