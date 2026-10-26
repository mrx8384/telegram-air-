package com.tcam.app.ui.screens

import android.content.Intent
import androidx.core.net.toUri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tcam.app.R
import com.tcam.app.service.RecordingState
import com.tcam.app.ui.MainViewModel
import com.tcam.app.ui.SuccessColor
import com.tcam.app.ui.WarningColor

@Composable
fun SettingsScreen(vm: MainViewModel, modifier: Modifier, onStartRecording: () -> Unit) {
    val context = LocalContext.current
    val recording by vm.recording.collectAsState()
    val overlayVisible by vm.overlayVisible.collectAsState()
    val env = vm.environment

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Telegram API credentials for this app.
        SectionCard(stringResource(R.string.api_title)) {
            Text(stringResource(R.string.api_explain), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            var id by rememberSaveable { mutableStateOf(vm.apiId.takeIf { it > 0 }?.toString() ?: "") }
            var hash by remember { mutableStateOf(vm.apiHash) }
            OutlinedTextField(
                value = id, onValueChange = { id = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.api_id)) },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = hash, onValueChange = { hash = it.trim() }, label = { Text(stringResource(R.string.api_hash)) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { vm.saveApi(id, hash) }) { Text(stringResource(R.string.save)) }
        }

        // Language
        SectionCard(stringResource(R.string.language_title)) {
            var current by remember { mutableStateOf(vm.currentLanguage()) }
            listOf(null to stringResource(R.string.lang_system), "en" to "English", "fa" to "فارسی").forEach { (tag, name) ->
                Row(
                    Modifier.fillMaxWidth().selectable(selected = current == tag, role = Role.RadioButton) {
                        current = tag
                        vm.setLanguage(tag)
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = current == tag, onClick = null)
                    Text(name, Modifier.padding(start = 8.dp))
                }
            }
        }

        // Permissions
        SectionCard(stringResource(R.string.permissions_title)) {
            PermissionRow(stringResource(R.string.perm_accessibility), env.accessibilityOn) {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            PermissionRow(stringResource(R.string.perm_overlay), env.overlayAllowed) {
                context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri()))
            }
            PermissionRow(stringResource(R.string.perm_notifications), env.notificationsAllowed) {
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }
            Text(stringResource(R.string.restricted_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
            }) { Text("App info") }
        }

        // Floating controls
        SectionCard(stringResource(R.string.floating_title)) {
            Text(stringResource(R.string.floating_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(if (overlayVisible) R.string.floating_hide else R.string.floating_show), Modifier.weight(1f))
                Switch(
                    checked = overlayVisible,
                    enabled = env.overlayAllowed,
                    onCheckedChange = { vm.showOverlay(it) },
                )
            }
        }

        // Screen recording
        SectionCard(stringResource(R.string.recording_title)) {
            Text(stringResource(R.string.recording_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when (val r = recording) {
                RecordingState.Idle -> Text(stringResource(R.string.recording_off))
                RecordingState.Recording -> Text(stringResource(R.string.recording_on), color = MaterialTheme.colorScheme.error)
                is RecordingState.Saved -> Text(stringResource(R.string.recording_saved, r.name), color = SuccessColor)
                is RecordingState.Failed -> Text(stringResource(R.string.recording_failed, r.reason), color = MaterialTheme.colorScheme.error)
            }
            if (recording == RecordingState.Recording) {
                OutlinedButton(onClick = vm::stopRecording) { Text(stringResource(R.string.record_stop)) }
            } else {
                Button(onClick = onStartRecording) { Text(stringResource(R.string.record_start)) }
            }
        }
    }
}

@Composable
private fun PermissionRow(title: String, granted: Boolean, onOpen: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(
                stringResource(if (granted) R.string.granted else R.string.not_granted),
                style = MaterialTheme.typography.bodySmall,
                color = if (granted) SuccessColor else WarningColor,
            )
        }
        OutlinedButton(onClick = onOpen) { Text(stringResource(R.string.open_settings)) }
    }
}
