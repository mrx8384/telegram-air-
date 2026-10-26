package com.tcam.app.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tcam.app.R
import com.tcam.app.data.LogLine
import com.tcam.app.ui.MainViewModel
import com.tcam.app.ui.SuccessColor
import com.tcam.app.ui.WarningColor
import com.tcam.app.ui.description
import com.tcam.app.ui.label
import com.tcam.core.batch.BatchState
import com.tcam.core.batch.ChannelTask
import com.tcam.core.engine.LogLevel
import com.tcam.core.model.AutomationMode
import com.tcam.core.model.ChannelAction
import com.tcam.core.model.ChannelCandidate
import com.tcam.core.safety.SafetyPolicy
import com.tcam.core.state.BatchPhase
import com.tcam.core.state.TaskState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChannelsScreen(vm: MainViewModel, modifier: Modifier) {
    val batch by vm.batch.collectAsState()
    val logs by vm.logs.collectAsState()
    val auth by vm.auth.collectAsState()
    val service by vm.accessibilityService.collectAsState()
    val editable = batch.phase in setOf(BatchPhase.IDLE, BatchPhase.FINISHED, BatchPhase.STOPPED)

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 12.dp).testTag("channels_list"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Spacer(Modifier.size(4.dp)) }
        item {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        item { InputCard(vm, editable) }
        item {
            ModeCard(
                vm = vm,
                enabled = editable,
                tdlibReady = auth == com.tcam.tdlib.AuthStep.Ready,
                accessibilityOn = service != null,
            )
        }
        item { ActionCard(vm, editable) }
        item { ControlsCard(vm, batch) }
        if (batch.tasks.isNotEmpty()) {
            item { Text(stringResource(R.string.tasks_title), style = MaterialTheme.typography.titleMedium) }
            items(batch.tasks, key = { it.id }) { task -> TaskCard(vm, task, batch.phase) }
        }
        if (batch.phase == BatchPhase.FINISHED || batch.phase == BatchPhase.STOPPED) {
            item { ReportCard(vm, batch) }
        }
        item { LogCard(logs) }
        item { Spacer(Modifier.size(12.dp)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InputCard(vm: MainViewModel, enabled: Boolean) {
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.importFile(it) }
    }
    SectionCard(stringResource(R.string.channels_title)) {
        OutlinedTextField(
            value = vm.inputText,
            onValueChange = { vm.inputText = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.input_hint)) },
            minLines = 3,
            enabled = enabled,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::addFromInput, enabled = enabled && vm.inputText.isNotBlank()) { Text(stringResource(R.string.add)) }
            OutlinedButton(onClick = { importer.launch(arrayOf("text/plain", "text/*")) }, enabled = enabled) {
                Text(stringResource(R.string.import_txt))
            }
            TextButton(onClick = vm::clearQueue, enabled = enabled && vm.queue.isNotEmpty()) { Text(stringResource(R.string.clear)) }
        }
        if (vm.queue.isNotEmpty()) {
            Text(stringResource(R.string.queued_count, vm.queue.size), style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                vm.queue.forEach { input ->
                    InputChip(
                        selected = false,
                        onClick = { if (enabled) vm.remove(input) },
                        label = { Text(input.displayText, maxLines = 1) },
                        trailingIcon = { Icon(Icons.Filled.Close, contentDescription = null, Modifier.size(16.dp)) },
                        enabled = enabled,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeCard(vm: MainViewModel, enabled: Boolean, tdlibReady: Boolean, accessibilityOn: Boolean) {
    SectionCard(stringResource(R.string.mode_title)) {
        val modes = listOf(AutomationMode.AUTO, AutomationMode.BACKGROUND, AutomationMode.SCREEN)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            modes.forEachIndexed { i, m ->
                SegmentedButton(
                    selected = vm.mode == m,
                    onClick = { vm.updateMode(m) },
                    shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                    enabled = enabled,
                ) { Text(stringResource(m.label()), maxLines = 1) }
            }
        }
        Text(stringResource(vm.mode.description()), style = MaterialTheme.typography.bodySmall)
        StatusLine(tdlibReady, R.string.status_tdlib_ready, R.string.status_tdlib_not)
        StatusLine(accessibilityOn, R.string.status_access_on, R.string.status_access_off)
        StatusLine(vm.environment.telegramInstalled, R.string.status_tg_installed, R.string.status_tg_missing)
    }
}

@Composable
private fun StatusLine(ok: Boolean, okText: Int, badText: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("●", color = if (ok) SuccessColor else WarningColor)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(if (ok) okText else badText), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ActionCard(vm: MainViewModel, enabled: Boolean) {
    SectionCard(stringResource(R.string.action_title)) {
        ChannelAction.entries.forEach { a ->
            Row(
                Modifier.fillMaxWidth()
                    .selectable(selected = vm.action == a, enabled = enabled, role = Role.RadioButton, onClick = { vm.updateAction(a) })
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.Top,
            ) {
                RadioButton(selected = vm.action == a, onClick = null, enabled = enabled, modifier = Modifier.padding(top = 2.dp))
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        stringResource(a.label()),
                        fontWeight = FontWeight.Medium,
                        color = if (a == ChannelAction.DELETE_OWNED) MaterialTheme.colorScheme.error else Color.Unspecified,
                    )
                    Text(stringResource(a.description()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.dry_run), fontWeight = FontWeight.Medium)
                Text(stringResource(R.string.dry_run_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = vm.dryRun, onCheckedChange = vm::updateDryRun, enabled = enabled)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ControlsCard(vm: MainViewModel, batch: BatchState) {
    SectionCard(stringResource(batch.phase.label())) {
        batch.engine?.let { Text(stringResource(R.string.method_used, it.name), style = MaterialTheme.typography.bodySmall) }
        batch.engineExplanation?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (batch.tasks.isNotEmpty()) {
            LinearProgressIndicator(progress = { batch.progress }, modifier = Modifier.fillMaxWidth())
            Text("${batch.finishedCount} / ${batch.tasks.size}", style = MaterialTheme.typography.bodySmall)
        }
        val busy = batch.phase == BatchPhase.RUNNING || batch.phase == BatchPhase.RESOLVING || batch.phase == BatchPhase.PAUSED
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when (batch.phase) {
                BatchPhase.IDLE, BatchPhase.FINISHED, BatchPhase.STOPPED -> {
                    Button(onClick = vm::start) { Text(stringResource(R.string.start)) }
                    if (batch.tasks.isNotEmpty()) OutlinedButton(onClick = vm::newBatch) { Text(stringResource(R.string.new_batch)) }
                }
                BatchPhase.AWAITING_USER -> {
                    val n = batch.tasks.count { it.state == TaskState.AWAITING_CONFIRMATION && it.included }
                    Button(onClick = vm::confirmAndRun) { Text(stringResource(R.string.confirm_run, n)) }
                }
                BatchPhase.PAUSED -> Button(onClick = vm::resume) { Text(stringResource(R.string.resume)) }
                else -> OutlinedButton(onClick = vm::pause) { Text(stringResource(R.string.pause)) }
            }
            if (busy || batch.phase == BatchPhase.AWAITING_USER) {
                Button(
                    onClick = vm::stop,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                ) { Text(stringResource(R.string.stop)) }
            }
        }
    }
}

@Composable
private fun stateColor(state: TaskState): Color = when (state) {
    TaskState.SUCCEEDED -> SuccessColor
    TaskState.FAILED, TaskState.BLOCKED -> MaterialTheme.colorScheme.error
    TaskState.NEEDS_SELECTION, TaskState.AWAITING_CONFIRMATION, TaskState.UNVERIFIED -> WarningColor
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun candidateLine(c: ChannelCandidate): String {
    val parts = mutableListOf(c.label, stringResource(c.role.label()))
    c.memberCount?.let { parts += stringResource(R.string.subscribers, it) }
    return parts.joinToString(" · ")
}

@Composable
private fun TaskCard(vm: MainViewModel, task: ChannelTask, phase: BatchPhase) {
    var showDelete by remember { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("#${task.id}  ${task.input.displayText}", Modifier.weight(1f), fontWeight = FontWeight.Medium, maxLines = 2)
                AssistChip(
                    onClick = {},
                    label = { Text(stringResource(task.state.label())) },
                    colors = AssistChipDefaults.assistChipColors(labelColor = stateColor(task.state)),
                )
            }
            task.candidate?.let { Text(candidateLine(it), style = MaterialTheme.typography.bodySmall) }
            val failure = task.failureKind
            if (failure != null) {
                Text(stringResource(failure.label()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            task.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }

            if (task.state == TaskState.NEEDS_SELECTION) {
                Text(stringResource(R.string.pick_channel), style = MaterialTheme.typography.bodyMedium)
                task.options.forEach { option ->
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.select(task.id, option.key) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = false, onClick = { vm.select(task.id, option.key) })
                        Text(candidateLine(option), style = MaterialTheme.typography.bodySmall)
                    }
                }
                TextButton(onClick = { vm.skip(task.id) }) { Text(stringResource(R.string.skip)) }
            }

            if (task.state == TaskState.AWAITING_CONFIRMATION && phase == BatchPhase.AWAITING_USER) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = task.included, onCheckedChange = { vm.setIncluded(task.id, it) })
                    Text(stringResource(R.string.include))
                }
                if (task.action == ChannelAction.DELETE_OWNED && task.included) {
                    if (task.deletionConfirmed) {
                        Text(stringResource(R.string.deletion_confirmed), color = WarningColor, style = MaterialTheme.typography.bodySmall)
                    } else {
                        Button(
                            onClick = { showDelete = true },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer),
                        ) { Text(stringResource(R.string.confirm_delete)) }
                    }
                }
            }
        }
    }
    val candidate = task.candidate
    if (showDelete && candidate != null) {
        DeleteConfirmDialog(
            candidate = candidate,
            onConfirm = { typed -> vm.confirmDeletion(task.id, typed).also { if (it) showDelete = false } },
            onDismiss = { showDelete = false },
        )
    }
}

@Composable
private fun DeleteConfirmDialog(candidate: ChannelCandidate, onConfirm: (String) -> Boolean, onDismiss: () -> Unit) {
    var typed by rememberSaveable { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    val phrase = SafetyPolicy.deletionConfirmationPhrase(candidate)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.delete_dialog_text, candidate.label, phrase))
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it; wrong = false },
                    singleLine = true,
                    label = { Text(stringResource(R.string.delete_dialog_field)) },
                    isError = wrong,
                    supportingText = { if (wrong) Text(stringResource(R.string.wrong_confirmation)) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { wrong = !onConfirm(typed) }) {
                Text(stringResource(R.string.delete_confirm_btn), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ReportCard(vm: MainViewModel, batch: BatchState) {
    val context = LocalContext.current
    val text = remember(batch) { vm.reportText() }
    SectionCard(stringResource(R.string.report_title)) {
        SelectionContainer { Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
        OutlinedButton(onClick = {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            context.startActivity(Intent.createChooser(send, null))
        }) { Text(stringResource(R.string.share_report)) }
    }
}

@Composable
private fun LogCard(logs: List<LogLine>) {
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    SectionCard(stringResource(R.string.logs_title)) {
        if (logs.isEmpty()) {
            Text(stringResource(R.string.no_logs), style = MaterialTheme.typography.bodySmall)
        } else {
            SelectionContainer {
                Column {
                    logs.takeLast(80).forEach { line ->
                        Text(
                            "${fmt.format(Date(line.time))}  ${line.message}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = when (line.level) {
                                LogLevel.ERROR -> MaterialTheme.colorScheme.error
                                LogLevel.WARN -> WarningColor
                                LogLevel.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
                                LogLevel.INFO -> MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            }
        }
    }
}
