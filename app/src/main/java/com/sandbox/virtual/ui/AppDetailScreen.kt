package com.sandbox.virtual.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sandbox.virtual.model.InstalledAppInfo
import com.sandbox.virtual.server.DeviceSpoofManager
import com.sandbox.virtual.ui.theme.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDetailScreen(
    app: InstalledAppInfo,
    viewModel: SandboxViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val allLogs by viewModel.securityLogs.collectAsState()
    val appLogs = remember(allLogs, app.packageName) {
        allLogs.filter { it.packageName == app.packageName || it.detail.contains(app.packageName) }
    }

    var showWipeConfirm by remember { mutableStateOf(false) }
    var showUninstallConfirm by remember { mutableStateOf(false) }

    val fakeAndroidId = remember(app.packageName) { DeviceSpoofManager.getFakeAndroidId(app.packageName) }
    val fakeImei = remember(app.packageName) { DeviceSpoofManager.getFakeImei(app.packageName) }
    val fakeMac = remember(app.packageName) { DeviceSpoofManager.getFakeMacAddress(app.packageName) }
    val fakeSerial = remember(app.packageName) { DeviceSpoofManager.getFakeSerial(app.packageName) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sandbox Control Panel", color = TextPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // App Header Card
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (app.iconPath != null && File(app.iconPath).exists()) {
                            val bitmap = remember(app.iconPath) { BitmapFactory.decodeFile(app.iconPath) }
                            if (bitmap != null) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = app.appName,
                                    modifier = Modifier
                                        .size(64.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                )
                            } else {
                                DefaultAppIcon()
                            }
                        } else {
                            DefaultAppIcon()
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Column {
                            Text(
                                text = app.appName,
                                style = MaterialTheme.typography.titleLarge,
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = app.packageName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                            Text(
                                text = "v${app.versionName} (Build ${app.versionCode})",
                                style = MaterialTheme.typography.labelSmall,
                                color = GreenPrimary
                            )
                        }
                    }
                }
            }

            // Primary Control Actions
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "PROCESS & CONTAINER CONTROLS",
                            style = MaterialTheme.typography.labelSmall,
                            color = GreenPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { viewModel.launchApp(app.packageName, context) },
                                colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary, contentColor = MaterialTheme.colorScheme.background),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Launch", fontWeight = FontWeight.Bold)
                            }

                            FilledTonalButton(
                                onClick = { viewModel.forceStop(app.packageName, context) },
                                colors = ButtonDefaults.filledTonalButtonColors(containerColor = AccentWarning.copy(alpha = 0.2f), contentColor = AccentWarning),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Force Stop", fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { showWipeConfirm = true },
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.CleaningServices, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Wipe Data")
                            }

                            OutlinedButton(
                                onClick = { showUninstallConfirm = true },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentRed),
                                border = androidx.compose.foundation.BorderStroke(1.dp, AccentRed),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Uninstall")
                            }
                        }
                    }
                }
            }

            // Sandbox Environment & Path Isolation Info
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "FILE SYSTEM & ISOLATION BOUNDARIES",
                            style = MaterialTheme.typography.labelSmall,
                            color = GreenPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        DetailRow(label = "Allocated Process", value = ":virtual_p${app.assignedProcessIndex}")
                        DetailRow(label = "Main Activity", value = app.mainActivity)
                        DetailRow(label = "Virtual Data Dir", value = app.dataDir)
                        DetailRow(label = "Virtual Lib Dir", value = app.libDir)
                        DetailRow(label = "APK Archive", value = app.apkPath)
                    }
                }
            }

            // Device Spoofing Info
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "SPOOFED DEVICE HARDWARE IDS",
                                style = MaterialTheme.typography.labelSmall,
                                color = GreenPrimary,
                                fontWeight = FontWeight.Bold
                            )
                            IconButton(onClick = { viewModel.regenerateSpoofedIds(app.packageName) }) {
                                Icon(Icons.Default.Refresh, contentDescription = "Regenerate", tint = GreenPrimary)
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))

                        DetailRow(label = "ANDROID_ID", value = fakeAndroidId)
                        DetailRow(label = "IMEI", value = fakeImei)
                        DetailRow(label = "MAC Address", value = fakeMac)
                        DetailRow(label = "Hardware Serial", value = fakeSerial)
                    }
                }
            }

            // Security Logs for this App
            item {
                Text(
                    text = "SANDBOX ACTIVITY & INTERCEPTION LOGS (${appLogs.size})",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            if (appLogs.isEmpty()) {
                item {
                    Text(
                        text = "No security events recorded yet for this app.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            } else {
                items(appLogs.take(15)) { log ->
                    SecurityLogItem(log = log)
                }
            }
        }

        // Wipe Confirmation Dialog
        if (showWipeConfirm) {
            AlertDialog(
                onDismissRequest = { showWipeConfirm = false },
                title = { Text("Wipe Sandbox Data?", color = TextPrimary) },
                text = { Text("This will delete all files, databases, and cache for ${app.appName} without uninstalling the APK.", color = TextSecondary) },
                confirmButton = {
                    Button(
                        onClick = {
                            showWipeConfirm = false
                            viewModel.wipeData(app.packageName)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentWarning)
                    ) {
                        Text("Wipe Data", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showWipeConfirm = false }) {
                        Text("Cancel", color = TextSecondary)
                    }
                }
            )
        }

        // Uninstall Confirmation Dialog
        if (showUninstallConfirm) {
            AlertDialog(
                onDismissRequest = { showUninstallConfirm = false },
                title = { Text("Uninstall Virtual App?", color = TextPrimary) },
                text = { Text("This will remove ${app.appName} and all its sandboxed files completely.", color = TextSecondary) },
                confirmButton = {
                    Button(
                        onClick = {
                            showUninstallConfirm = false
                            viewModel.uninstall(app.packageName)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentRed)
                    ) {
                        Text("Uninstall", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showUninstallConfirm = false }) {
                        Text("Cancel", color = TextSecondary)
                    }
                }
            )
        }
    }
}

@Composable
fun DetailRow(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = TextPrimary,
            fontSize = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun SecurityLogItem(log: com.sandbox.virtual.model.SecurityLog) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = log.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (log.blocked) AccentRed else GreenPrimary
                )
                val timeStr = remember(log.timestamp) {
                    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(log.timestamp))
                }
                Text(text = timeStr, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = log.detail,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = TextSecondary
            )
        }
    }
}
