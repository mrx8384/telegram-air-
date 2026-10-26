package com.tcam.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tcam.app.R
import com.tcam.app.data.RunEntity
import com.tcam.app.ui.MainViewModel
import java.text.DateFormat
import java.util.Date

@Composable
fun HistoryScreen(vm: MainViewModel, modifier: Modifier) {
    val runs by vm.runs.collectAsState(initial = emptyList())
    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Spacer(Modifier.size(4.dp)) }
        item { Text(stringResource(R.string.history_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        if (runs.isEmpty()) {
            item { Text(stringResource(R.string.history_empty)) }
        } else {
            item { TextButton(onClick = vm::clearHistory) { Text(stringResource(R.string.clear_history)) } }
            items(runs, key = { it.id }) { RunCard(it) }
        }
        item { Spacer(Modifier.size(12.dp)) }
    }
}

@Composable
private fun RunCard(run: RunEntity) {
    var expanded by remember { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(run.startedAt)), fontWeight = FontWeight.Medium)
            Text(
                "${run.action} · ${run.engine ?: "-"}${if (run.dryRun) " · dry run" else ""} · ${run.outcome}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("✓ ${run.succeeded}   ✗ ${run.failed}   Σ ${run.total}", style = MaterialTheme.typography.bodySmall)
            if (expanded) {
                SelectionContainer { Text(run.report, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
            }
        }
    }
}
