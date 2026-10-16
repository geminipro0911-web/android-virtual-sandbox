package com.sandbox.virtual.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sandbox.virtual.model.SecurityEventType
import com.sandbox.virtual.model.SecurityLog
import com.sandbox.virtual.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityLogScreen(
    viewModel: SandboxViewModel,
    onBack: () -> Unit
) {
    val logs by viewModel.securityLogs.collectAsState()
    var selectedFilter by remember { mutableStateOf<SecurityEventType?>(null) }

    val filteredLogs = remember(logs, selectedFilter) {
        if (selectedFilter == null) logs else logs.filter { it.eventType == selectedFilter }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Security & Isolation Monitor", color = TextPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.clearAllLogs() }) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Clear Logs", tint = AccentRed)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Stats Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatBadge(title = "Total Events", value = logs.size.toString(), modifier = Modifier.weight(1f))
                StatBadge(
                    title = "Blocked",
                    value = logs.count { it.blocked }.toString(),
                    color = AccentRed,
                    modifier = Modifier.weight(1f)
                )
                StatBadge(
                    title = "Redirected",
                    value = logs.count { it.eventType == SecurityEventType.FILE_ACCESS_REDIRECTED }.toString(),
                    color = GreenPrimary,
                    modifier = Modifier.weight(1f)
                )
            }

            // Filter Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedFilter == null,
                    onClick = { selectedFilter = null },
                    label = { Text("All (${logs.size})") }
                )
                FilterChip(
                    selected = selectedFilter == SecurityEventType.FILE_ACCESS_REDIRECTED,
                    onClick = { selectedFilter = SecurityEventType.FILE_ACCESS_REDIRECTED },
                    label = { Text("POSIX I/O") }
                )
                FilterChip(
                    selected = selectedFilter == SecurityEventType.PERMISSION_INTERCEPTED,
                    onClick = { selectedFilter = SecurityEventType.PERMISSION_INTERCEPTED },
                    label = { Text("Permissions") }
                )
                FilterChip(
                    selected = selectedFilter == SecurityEventType.DEVICE_ID_SPOOFED,
                    onClick = { selectedFilter = SecurityEventType.DEVICE_ID_SPOOFED },
                    label = { Text("Device IDs") }
                )
                FilterChip(
                    selected = selectedFilter == SecurityEventType.IPC_INTERCEPTED,
                    onClick = { selectedFilter = SecurityEventType.IPC_INTERCEPTED },
                    label = { Text("Binder IPC") }
                )
                FilterChip(
                    selected = selectedFilter == SecurityEventType.PROCESS_LIFECYCLE,
                    onClick = { selectedFilter = SecurityEventType.PROCESS_LIFECYCLE },
                    label = { Text("Processes") }
                )
            }

            Divider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(top = 8.dp))

            if (filteredLogs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No events match the selected criteria.", color = TextSecondary)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredLogs, key = { it.id }) { log ->
                        FullSecurityLogCard(log = log)
                    }
                }
            }
        }
    }
}

@Composable
fun StatBadge(
    title: String,
    value: String,
    color: androidx.compose.ui.graphics.Color = TextPrimary,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(8.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = title, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color)
        }
    }
}

@Composable
fun FullSecurityLogCard(log: SecurityLog) {
    val (badgeColor, typeLabel) = when (log.eventType) {
        SecurityEventType.FILE_ACCESS_BLOCKED -> AccentRed to "BLOCKED"
        SecurityEventType.FILE_ACCESS_REDIRECTED -> GreenPrimary to "I/O REDIRECT"
        SecurityEventType.PERMISSION_INTERCEPTED -> AccentWarning to "PERMISSION"
        SecurityEventType.DEVICE_ID_SPOOFED -> GreenSecondary to "SPOOFED ID"
        SecurityEventType.IPC_INTERCEPTED -> GreenTertiary to "BINDER IPC"
        SecurityEventType.PROCESS_LIFECYCLE -> TextSecondary to "PROCESS"
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = badgeColor.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = typeLabel,
                        color = badgeColor,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                val timeStr = remember(log.timestamp) {
                    SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(log.timestamp))
                }
                Text(text = timeStr, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = log.summary,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )

            Text(
                text = "Target: ${log.packageName}",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )

            Spacer(modifier = Modifier.height(4.dp))

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = log.detail,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = TextPrimary,
                    modifier = Modifier.padding(8.dp)
                )
            }
        }
    }
}
