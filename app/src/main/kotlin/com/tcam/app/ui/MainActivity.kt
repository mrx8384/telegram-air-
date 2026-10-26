package com.tcam.app.ui

import android.Manifest
import android.app.Activity
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.tcam.app.service.ScreenRecordService

class MainActivity : AppCompatActivity() {
    private val vm: MainViewModel by viewModels()

    private val captureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            ScreenRecordService.start(this, result.resultCode, data)
        }
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.refreshEnvironment()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && savedInstanceState == null) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            TcamTheme {
                AppRoot(
                    vm = vm,
                    onStartRecording = { captureLauncher.launch(ScreenRecordService.captureIntent(this)) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshEnvironment()
    }
}
