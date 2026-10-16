package com.sandbox.virtual.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.sandbox.virtual.model.InstalledAppInfo
import com.sandbox.virtual.ui.theme.GreenPrimary
import com.sandbox.virtual.ui.theme.TextPrimary
import com.sandbox.virtual.ui.theme.TextSecondary
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SandboxDashboardScreen(
    viewModel: SandboxViewModel,
    onPickApkClicked: () -> Unit
) {
    val context = LocalContext.current
    val apps by viewModel.installedApps.collectAsState()
    val isInstalling by viewModel.isInstalling.collectAsState()
    val installProgress by viewModel.installProgress.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val hostApps by viewModel.hostApps.collectAsState()

    var showCloneDialog by remember { mutableStateOf(false) }
    var showFabMenu by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(statusMessage) {
        statusMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearStatusMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = "Shield",
                            tint = GreenPrimary,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Knox Virtual Sandbox",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = "Isolated Virtual Container Engine",
                                style = MaterialTheme.typography.labelSmall,
                                color = GreenPrimary
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refreshApps() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = TextPrimary)
                    }
                    IconButton(onClick = { viewModel.navigateTo(SandboxScreen.SECURITY_LOGS) }) {
                        Icon(Icons.Default.Security, contentDescription = "Security Logs", tint = GreenPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (showFabMenu) {
                    ExtendedFloatingActionButton(
                        onClick = {
                            showFabMenu = false
                            viewModel.loadHostApps(context)
                            showCloneDialog = true
                        },
                        icon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                        text = { Text("Clone Host App") },
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = TextPrimary,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    ExtendedFloatingActionButton(
                        onClick = {
                            showFabMenu = false
                            onPickApkClicked()
                        },
                        icon = { Icon(Icons.Default.FileOpen, contentDescription = null) },
                        text = { Text("Pick APK File") },
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = TextPrimary,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                FloatingActionButton(
                    onClick = { showFabMenu = !showFabMenu },
                    containerColor = GreenPrimary,
                    contentColor = MaterialTheme.colorScheme.background
                ) {
                    Icon(
                        imageVector = if (showFabMenu) Icons.Default.Close else Icons.Default.Add,
                        contentDescription = "Install App"
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (apps.isEmpty()) {
                EmptyStateView(onPickApk = onPickApkClicked)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        EngineStatusHeader(appsCount = apps.size)
                    }
                    items(apps, key = { it.packageName }) { app ->
                        VirtualAppCard(
                            app = app,
                            onLaunch = { viewModel.launchApp(app.packageName, context) },
                            onSettings = { viewModel.selectApp(app) }
                        )
                    }
                }
            }

            // Installation Progress Dialog
            if (isInstalling) {
                Dialog(onDismissRequest = {}) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().padding(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(color = GreenPrimary)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Virtualizing Package",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = installProgress,
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            // Clone Host App Dialog
            if (showCloneDialog) {
                AlertDialog(
                    onDismissRequest = { showCloneDialog = false },
                    title = { Text("Clone Installed App", color = TextPrimary) },
                    text = {
                        if (hostApps.isEmpty()) {
                            Box(modifier = Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = GreenPrimary)
                            }
                        } else {
                            LazyColumn(modifier = Modifier.heightIn(max = 350.dp)) {
                                items(hostApps) { hostApp ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                showCloneDialog = false
                                                viewModel.cloneApp(hostApp.packageName)
                                            }
                                            .padding(vertical = 10.dp, horizontal = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.Android,
                                            contentDescription = null,
                                            tint = GreenPrimary,
                                            modifier = Modifier.size(32.dp)
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column {
                                            Text(hostApp.appName, fontWeight = FontWeight.Medium, color = TextPrimary)
                                            Text(hostApp.packageName, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                                        }
                                    }
                                    Divider(color = MaterialTheme.colorScheme.surfaceVariant)
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showCloneDialog = false }) {
                            Text("Cancel", color = TextSecondary)
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun EngineStatusHeader(appsCount: Int) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "SANDBOX STATUS",
                    style = MaterialTheme.typography.labelSmall,
                    color = GreenPrimary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "$appsCount Container Instances Active",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
            }
            Surface(
                color = GreenPrimary.copy(alpha = 0.15f),
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, GreenPrimary)
            ) {
                Text(
                    text = "POSIX I/O Hooked",
                    color = GreenPrimary,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
fun VirtualAppCard(
    app: InstalledAppInfo,
    onLaunch: () -> Unit,
    onSettings: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // App Icon
                if (app.iconPath != null && File(app.iconPath).exists()) {
                    val bitmap = remember(app.iconPath) {
                        BitmapFactory.decodeFile(app.iconPath)
                    }
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = app.appName,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )
                    } else {
                        DefaultAppIcon()
                    }
                } else {
                    DefaultAppIcon()
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = app.appName,
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = app.packageName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Running Status Chip
                Surface(
                    color = if (app.isRunning) GreenPrimary.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(if (app.isRunning) GreenPrimary else Color.Gray)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (app.isRunning) "RUNNING" else "STOPPED",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (app.isRunning) GreenPrimary else TextSecondary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Action Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Slot: :virtual_p${app.assignedProcessIndex} • v${app.versionName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )

                Row {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = TextSecondary)
                    }
                    Button(
                        onClick = onLaunch,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = GreenPrimary,
                            contentColor = MaterialTheme.colorScheme.background
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Launch", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun DefaultAppIcon() {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.Android, contentDescription = null, tint = GreenPrimary, modifier = Modifier.size(28.dp))
    }
}

@Composable
fun EmptyStateView(onPickApk: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Shield,
                contentDescription = null,
                tint = GreenPrimary,
                modifier = Modifier.size(54.dp)
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Virtual Container Ready",
            style = MaterialTheme.typography.titleLarge,
            color = TextPrimary,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Install any APK file or clone a host application into this isolated sandbox. Apps run with redirected POSIX I/O and spoofed device hardware IDs.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onPickApk,
            colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary, contentColor = MaterialTheme.colorScheme.background),
            shape = RoundedCornerShape(8.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Install Guest APK", fontWeight = FontWeight.Bold)
        }
    }
}
