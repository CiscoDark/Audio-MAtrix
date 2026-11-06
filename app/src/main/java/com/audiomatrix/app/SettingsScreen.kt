package com.audiomatrix.app

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    uiState: AudioMatrixUiState,
    onNavigateBack: () -> Unit,
    onSetThemeMode: (ThemeMode) -> Unit,
    onSetDynamicColor: (Boolean) -> Unit,
    onSetAmoledBlack: (Boolean) -> Unit,
    onSetAutoSilentBypass: (Boolean) -> Unit,
    onSetHybridZeroLagHal: (Boolean) -> Unit,
    onSetBufferBurstPreset: (BufferBurstPreset) -> Unit,
    onSetAutoFocusLockNewApps: (Boolean) -> Unit,
    onSetKeepAliveScreenOff: (Boolean) -> Unit,
    onRequestShizuku: () -> Unit,
    onRunSilentBypassNow: () -> Unit,
    onRequestSinglePermission: (String) -> Unit,
    onRequestAllPermissions: () -> Unit,
    onOpenSystemAppSettings: () -> Unit,
    onResetAllRouting: () -> Unit,
    onClearCommandLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onNavigateBack)

    var showCommandAuditLogs by rememberSaveable { mutableStateOf(true) }
    val timeFormatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 640.dp)
                .testTag("settings_lazy_column"),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 8.dp,
                bottom = 36.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Appearance & Theme Card
            item(key = "settings_theme_card") {
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize()
                        .testTag("settings_theme_card"),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        SettingsCardHeader(
                            icon = Icons.Default.Palette,
                            title = "Appearance & Theme",
                            subtitle = "Customize Material You dynamic color and dark console surfaces"
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        Text(
                            text = "Theme Mode",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        SingleChoiceSegmentedButtonRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("theme_mode_segmented_row")
                        ) {
                            ThemeMode.entries.forEachIndexed { index, mode ->
                                SegmentedButton(
                                    selected = uiState.settings.themeMode == mode,
                                    onClick = { onSetThemeMode(mode) },
                                    shape = SegmentedButtonDefaults.itemShape(
                                        index = index,
                                        count = ThemeMode.entries.size
                                    ),
                                    label = {
                                        Text(
                                            text = mode.label,
                                            style = MaterialTheme.typography.labelLarge
                                        )
                                    },
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("theme_mode_${mode.name.lowercase()}")
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                        Spacer(modifier = Modifier.height(10.dp))

                        SettingsToggleRow(
                            title = "Material You Dynamic Palette",
                            subtitle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                "Derive console accent colors from your Android 12+ system wallpaper"
                            } else {
                                "Uses AudioMatrix Cyan/Indigo studio palette on pre-Android 12 devices"
                            },
                            checked = uiState.settings.useDynamicColor,
                            onCheckedChange = onSetDynamicColor,
                            testTag = "switch_dynamic_color"
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        SettingsToggleRow(
                            title = "Pure AMOLED Pitch-Black Surface",
                            subtitle = "Use #000000 background in Dark Mode to reduce OLED power draw",
                            checked = uiState.settings.amoledPitchBlack,
                            onCheckedChange = onSetAmoledBlack,
                            testTag = "switch_amoled_black"
                        )
                    }
                }
            }

            // 2. Permissions & Shizuku Privileged Access Card
            item(key = "settings_permissions_card") {
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize()
                        .testTag("settings_permissions_card"),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        SettingsCardHeader(
                            icon = Icons.Default.Security,
                            title = "Permissions & Shizuku Center",
                            subtitle = "Manage privileged binder access, silent PROJECT_MEDIA bypass & OS permissions"
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        // Shizuku Binder Row
                        val shizukuGranted =
                            uiState.shizukuStatus == ShizukuStatus.BINDER_ALIVE_GRANTED
                        PermissionStatusRow(
                            icon = Icons.Default.Security,
                            title = "Shizuku Privileged Binder (UID 2000)",
                            subtitle = when (uiState.shizukuStatus) {
                                ShizukuStatus.BINDER_ALIVE_GRANTED ->
                                    "Connected & authorized for appops and IAudioService HAL routing"
                                ShizukuStatus.BINDER_ALIVE_DENIED ->
                                    "Binder detected • Tap Grant to authorize AudioMatrix"
                                ShizukuStatus.BINDER_DEAD_FALLBACK ->
                                    "Shizuku service inactive on host • Running in fallback mode"
                            },
                            isGranted = shizukuGranted,
                            actionLabel = if (shizukuGranted) "Verify" else "Bind",
                            onActionClick = onRequestShizuku,
                            testTag = "permission_row_shizuku"
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // Silent PROJECT_MEDIA Bypass Row
                        PermissionStatusRow(
                            icon = Icons.Default.GraphicEq,
                            title = "Zero-Popup Screen-Cast Bypass (PROJECT_MEDIA)",
                            subtitle = "Pre-authorizes PROJECT_MEDIA & RECORD_AUDIO via appops to skip the cast dialog",
                            isGranted = uiState.silentBypassActive,
                            actionLabel = "Run Grant",
                            onActionClick = onRunSilentBypassNow,
                            testTag = "permission_row_silent_bypass"
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // RECORD_AUDIO Runtime Permission
                        PermissionStatusRow(
                            icon = Icons.Default.Mic,
                            title = "Internal Audio Capture (RECORD_AUDIO)",
                            subtitle = "Required by AudioPlaybackCaptureConfiguration to read per-app PCM streams",
                            isGranted = uiState.permissions.recordAudioGranted,
                            actionLabel = "Grant",
                            onActionClick = {
                                onRequestSinglePermission(Manifest.permission.RECORD_AUDIO)
                            },
                            testTag = "permission_row_record_audio"
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // BLUETOOTH_CONNECT Runtime Permission
                        PermissionStatusRow(
                            icon = Icons.Default.BluetoothAudio,
                            title = "Bluetooth Sink Control (BLUETOOTH_CONNECT)",
                            subtitle = "Required to inspect connected A2DP / BLE earbuds and dual audio sinks",
                            isGranted = uiState.permissions.bluetoothConnectGranted,
                            actionLabel = "Grant",
                            onActionClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    onRequestSinglePermission(Manifest.permission.BLUETOOTH_CONNECT)
                                } else {
                                    onRequestAllPermissions()
                                }
                            },
                            testTag = "permission_row_bluetooth"
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // POST_NOTIFICATIONS Runtime Permission
                        PermissionStatusRow(
                            icon = Icons.Default.Notifications,
                            title = "Foreground Engine Notification (POST_NOTIFICATIONS)",
                            subtitle = "Keeps AudioRouterService alive with real-time FastMixer telemetry",
                            isGranted = uiState.permissions.postNotificationsGranted,
                            actionLabel = "Grant",
                            onActionClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    onRequestSinglePermission(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    onRequestAllPermissions()
                                }
                            },
                            testTag = "permission_row_notifications"
                        )

                        Spacer(modifier = Modifier.height(14.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                        Spacer(modifier = Modifier.height(10.dp))

                        SettingsToggleRow(
                            title = "Auto-Run Silent AppOps Bypass on Launch",
                            subtitle = "Automatically execute PROJECT_MEDIA & RECORD_AUDIO allow when Shizuku connects",
                            checked = uiState.settings.autoSilentBypassOnLaunch,
                            onCheckedChange = onSetAutoSilentBypass,
                            testTag = "switch_auto_silent_bypass"
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = onRequestAllPermissions,
                                modifier = Modifier
                                    .weight(1f)
                                    .minimumInteractiveComponentSize()
                                    .testTag("grant_all_permissions_button")
                            ) {
                                Text("Grant All Permissions")
                            }

                            OutlinedButton(
                                onClick = onOpenSystemAppSettings,
                                modifier = Modifier
                                    .weight(1f)
                                    .minimumInteractiveComponentSize()
                                    .testTag("open_system_settings_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.OpenInNew,
                                    contentDescription = "System Settings",
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("OS App Info")
                            }
                        }
                    }
                }
            }

            // 3. Low-Latency Audio Engine Tuning Card
            item(key = "settings_engine_tuning_card") {
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize()
                        .testTag("settings_engine_card"),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        SettingsCardHeader(
                            icon = Icons.Default.Speed,
                            title = "Low-Latency FastMixer Engine",
                            subtitle = "Configure hardware burst size, 0ms HAL affinity, and focus lock automation"
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        Text(
                            text = "FastMixer Micro-Buffer Burst Size",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        SingleChoiceSegmentedButtonRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("buffer_burst_segmented_row")
                        ) {
                            BufferBurstPreset.entries.forEachIndexed { index, preset ->
                                SegmentedButton(
                                    selected = uiState.settings.bufferBurstPreset == preset,
                                    onClick = { onSetBufferBurstPreset(preset) },
                                    shape = SegmentedButtonDefaults.itemShape(
                                        index = index,
                                        count = BufferBurstPreset.entries.size
                                    ),
                                    label = {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(
                                                text = preset.label,
                                                style = MaterialTheme.typography.labelSmall,
                                                maxLines = 1
                                            )
                                            Text(
                                                text = preset.latencyHint,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("burst_preset_${preset.name.lowercase()}")
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                        Spacer(modifier = Modifier.height(10.dp))

                        SettingsToggleRow(
                            title = "Hybrid 0ms Native HAL Routing",
                            subtitle = "Use IAudioService.setUidDeviceAffinity for single-sink STEREO streams with zero PCM capture delay",
                            checked = uiState.settings.hybridZeroLagHalRouting,
                            onCheckedChange = onSetHybridZeroLagHal,
                            testTag = "switch_hybrid_zero_lag"
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        SettingsToggleRow(
                            title = "Auto-Lock Audio Focus for New Apps",
                            subtitle = "Automatically apply TAKE_AUDIO_FOCUS ignore when a new active audio stream starts",
                            checked = uiState.settings.autoFocusLockNewApps,
                            onCheckedChange = onSetAutoFocusLockNewApps,
                            testTag = "switch_auto_focus_lock"
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        SettingsToggleRow(
                            title = "Keep Routing Engine Active on Screen Off",
                            subtitle = "Maintain foreground MediaProjection audio threads while device is locked",
                            checked = uiState.settings.keepAliveOnScreenOff,
                            onCheckedChange = onSetKeepAliveScreenOff,
                            testTag = "switch_keep_alive_screen_off"
                        )
                    }
                }
            }

            // 4. Diagnostics, Reset & Shizuku Command Audit Log Card
            item(key = "settings_diagnostics_card") {
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize()
                        .testTag("settings_diagnostics_card"),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        SettingsCardHeader(
                            icon = Icons.Default.Terminal,
                            title = "Engine Telemetry & Matrix Reset",
                            subtitle = "Inspect native AudioFlinger metrics, reset routing rules, or audit Shizuku commands"
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                DiagnosticMetricRow(
                                    "Native Hardware Sample Rate",
                                    "${uiState.telemetry.sampleRateHz} Hz (PCM_16BIT)"
                                )
                                DiagnosticMetricRow(
                                    "Micro-Buffer Chunk Size",
                                    "${uiState.telemetry.bufferSizeSamples} shorts (${uiState.settings.bufferBurstPreset.latencyHint})"
                                )
                                DiagnosticMetricRow(
                                    "Active UrgentAudio Threads",
                                    "${uiState.telemetry.activePipelineCount}"
                                )
                                DiagnosticMetricRow(
                                    "Processed Stereo Frames",
                                    "${uiState.telemetry.processedFrames}"
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            FilledTonalButton(
                                onClick = onResetAllRouting,
                                modifier = Modifier
                                    .weight(1f)
                                    .minimumInteractiveComponentSize()
                                    .testTag("reset_all_routing_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.RestartAlt,
                                    contentDescription = "Reset Matrix",
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Reset All Routing")
                            }

                            OutlinedButton(
                                onClick = onClearCommandLogs,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("clear_shizuku_logs_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DeleteSweep,
                                    contentDescription = "Clear Logs",
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Clear Log")
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Shizuku & AppOps Command Log (${uiState.commandLogs.size})",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                            TextButton(
                                onClick = { showCommandAuditLogs = !showCommandAuditLogs },
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Text(if (showCommandAuditLogs) "Hide" else "Show")
                            }
                        }

                        AnimatedVisibility(
                            visible = showCommandAuditLogs,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(top = 4.dp)
                            ) {
                                if (uiState.commandLogs.isEmpty()) {
                                    Text(
                                        text = "No shell/binder commands logged in this session.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    uiState.commandLogs.take(12).forEach { log ->
                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = MaterialTheme.colorScheme.surfaceContainer,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Column(modifier = Modifier.padding(10.dp)) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    Text(
                                                        text = timeFormatter.format(Date(log.timestampMs)),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                    Text(
                                                        text = if (log.isSimulated) "FALLBACK" else "SHIZUKU",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = if (log.isSimulated) {
                                                            MaterialTheme.colorScheme.secondary
                                                        } else {
                                                            MaterialTheme.colorScheme.tertiary
                                                        }
                                                    )
                                                }
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Text(
                                                    text = "$ ${log.command}",
                                                    style = MaterialTheme.typography.labelLarge,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                                Text(
                                                    text = log.output,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsCardHeader(
    icon: ImageVector,
    title: String,
    subtitle: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(44.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag(testTag)
        )
    }
}

@Composable
private fun PermissionStatusRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    isGranted: Boolean,
    actionLabel: String,
    onActionClick: () -> Unit,
    testTag: String
) {
    val containerColor by animateColorAsState(
        targetValue = if (isGranted) {
            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "perm_row_bg"
    )

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isGranted) Icons.Default.CheckCircle else icon,
                contentDescription = title,
                tint = if (isGranted) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.size(22.dp)
            )

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            if (isGranted) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.tertiaryContainer
                ) {
                    Text(
                        text = "READY",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            } else {
                FilledTonalButton(
                    onClick = onActionClick,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("${testTag}_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.WarningAmber,
                        contentDescription = actionLabel,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = actionLabel,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}

@Composable
private fun DiagnosticMetricRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
    }
}
