package com.tcam.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.tcam.app.R
import com.tcam.app.ui.screens.AccountScreen
import com.tcam.app.ui.screens.ChannelsScreen
import com.tcam.app.ui.screens.HistoryScreen
import com.tcam.app.ui.screens.SettingsScreen

private data class Tab(val title: Int, val icon: ImageVector)

@Suppress("DEPRECATION")
private val TABS = listOf(
    Tab(R.string.tab_main, Icons.Filled.Home),
    Tab(R.string.tab_account, Icons.Filled.Person),
    Tab(R.string.tab_history, Icons.Filled.List),
    Tab(R.string.tab_settings, Icons.Filled.Settings),
)

@Composable
fun AppRoot(vm: MainViewModel, onStartRecording: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                TABS.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(stringResource(t.title)) },
                    )
                }
            }
        },
    ) { padding ->
        val m = Modifier.padding(padding)
        when (tab) {
            0 -> ChannelsScreen(vm, m)
            1 -> AccountScreen(vm, m)
            2 -> HistoryScreen(vm, m)
            else -> SettingsScreen(vm, m, onStartRecording)
        }
    }
}
