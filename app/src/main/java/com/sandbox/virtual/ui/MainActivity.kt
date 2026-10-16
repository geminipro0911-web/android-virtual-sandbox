package com.sandbox.virtual.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.sandbox.virtual.ui.theme.VirtualSandboxTheme

class MainActivity : ComponentActivity() {

    private val viewModel: SandboxViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            VirtualSandboxTheme {
                val currentScreen by viewModel.currentScreen.collectAsState()
                val selectedApp by viewModel.selectedApp.collectAsState()

                // Register file picker for APK files
                val apkPickerLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.GetContent()
                ) { uri ->
                    uri?.let {
                        viewModel.installApkFromUri(it, applicationContext)
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    when (currentScreen) {
                        SandboxScreen.DASHBOARD -> {
                            SandboxDashboardScreen(
                                viewModel = viewModel,
                                onPickApkClicked = {
                                    apkPickerLauncher.launch("*/*")
                                }
                            )
                        }
                        SandboxScreen.APP_DETAIL -> {
                            BackHandler {
                                viewModel.navigateTo(SandboxScreen.DASHBOARD)
                            }
                            selectedApp?.let { app ->
                                AppDetailScreen(
                                    app = app,
                                    viewModel = viewModel,
                                    onBack = {
                                        viewModel.navigateTo(SandboxScreen.DASHBOARD)
                                    }
                                )
                            } ?: run {
                                viewModel.navigateTo(SandboxScreen.DASHBOARD)
                            }
                        }
                        SandboxScreen.SECURITY_LOGS -> {
                            BackHandler {
                                viewModel.navigateTo(SandboxScreen.DASHBOARD)
                            }
                            SecurityLogScreen(
                                viewModel = viewModel,
                                onBack = {
                                    viewModel.navigateTo(SandboxScreen.DASHBOARD)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshApps()
    }
}
