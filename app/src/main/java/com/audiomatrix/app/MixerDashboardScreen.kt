package com.audiomatrix.app

import android.media.AudioDeviceInfo
import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import kotlin.math.roundToInt

@Composable
fun AudioMatrixTheme(
    settings: AppSettings = AppSettings(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val useDark = when (settings.themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val baseScheme = when {
        settings.useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (useDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        useDark -> StudioFallbackDarkScheme
        else -> StudioFallbackLightScheme
    }

    val finalScheme = if (useDark && settings.amoledPitchBlack) {
        baseScheme.withAmoledBlack()
    } else {
        baseScheme
    }

    MaterialTheme(
        colorScheme = finalScheme,
        typography = AudioMatrixTypography,
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MixerDashboardScreen(
    viewModel: AudioMatrixViewModel,
    onLaunchMediaProjection: () -> Unit,
    onRequestSinglePermission: (String) -> Unit = {},
    onRequestAllPermissions: () -> Unit = {},
    onOpenSystemAppSettings: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var activeDestination by rememberSaveable { mutableIntStateOf(0) }
    var showAppPickerSheet by rememberSaveable { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    if (showAppPickerSheet) {
        InstalledAppPickerDialog(
            installedApps = uiState.installedApps,
            onDismiss = { showAppPickerSheet = false },
            onSelectApp = { selectedApp ->
                viewModel.addAppToRouterList(selectedApp)
                showAppPickerSheet = false
            }
        )
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            LargeTopAppBar(
                navigationIcon = {
                    if (activeDestination == 1) {
                        IconButton(
                            onClick = { activeDestination = 0 },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("back_to_mixer_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back to Mixer"
                            )
                        }
                    }
                },
                title = {
                    Column {
                        Text(
                            text = if (activeDestination == 0) {
                                stringResource(R.string.app_name)
                            } else {
                                "Settings & Permissions"
                            },
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (activeDestination == 0) {
                                stringResource(R.string.subtitle_console)
                            } else {
                                "Themes, Shizuku AppOps, Runtime Permissions & Low-Latency FastMixer"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            viewModel.refreshRuntimePermissions()
                            viewModel.scanConnectedOutputs()
                            viewModel.scanActiveAppStreams()
                        },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("rescan_hardware_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.action_refresh_devices)
                        )
                    }
                    IconButton(
                        onClick = {
                            activeDestination = if (activeDestination == 0) 1 else 0
                        },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("top_bar_settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Open Settings",
                            tint = if (activeDestination == 1) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        },
        bottomBar = {
            NavigationBar(
                modifier = Modifier.testTag("main_bottom_nav")
            ) {
                NavigationBarItem(
                    selected = activeDestination == 0,
                    onClick = { activeDestination = 0 },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.GraphicEq,
                            contentDescription = "Mixer Console"
                        )
                    },
                    label = { Text("Mixer Console") },
                    modifier = Modifier.testTag("nav_item_mixer")
                )
                NavigationBarItem(
                    selected = activeDestination == 1,
                    onClick = {
                        viewModel.refreshRuntimePermissions()
                        activeDestination = 1
                    },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings & Permissions"
                        )
                    },
                    label = { Text("Settings & Permissions") },
                    modifier = Modifier.testTag("nav_item_settings")
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            if (activeDestination == 1) {
                SettingsScreen(
                    uiState = uiState,
                    onNavigateBack = { activeDestination = 0 },
                    onSetThemeMode = viewModel::setThemeMode,
                    onSetDynamicColor = viewModel::setUseDynamicColor,
                    onSetAmoledBlack = viewModel::setAmoledPitchBlack,
                    onSetAutoSilentBypass = viewModel::setAutoSilentBypassOnLaunch,
                    onSetHybridZeroLagHal = viewModel::setHybridZeroLagHalRouting,
                    onSetBufferBurstPreset = viewModel::setBufferBurstPreset,
                    onSetAutoFocusLockNewApps = viewModel::setAutoFocusLockNewApps,
                    onSetKeepAliveScreenOff = viewModel::setKeepAliveOnScreenOff,
                    onRequestShizuku = viewModel::requestShizukuPermission,
                    onRunSilentBypassNow = viewModel::runSilentBypassGrantNow,
                    onRequestSinglePermission = onRequestSinglePermission,
                    onRequestAllPermissions = onRequestAllPermissions,
                    onOpenSystemAppSettings = onOpenSystemAppSettings,
                    onResetAllRouting = viewModel::resetAllRoutingAndFocusLocks,
                    onClearCommandLogs = viewModel::clearShizukuLogs
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 640.dp)
                        .testTag("mixer_dashboard_lazy_column"),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 8.dp,
                        bottom = 36.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // 1. Top Engine & Shizuku Control Card
                    item(key = "engine_shizuku_master_card") {
                        EngineAndShizukuControlCard(
                            shizukuStatus = uiState.shizukuStatus,
                            silentBypassActive = uiState.silentBypassActive,
                            isEngineRunning = uiState.isEngineRunning,
                            telemetry = uiState.telemetry,
                            onGrantShizuku = { viewModel.requestShizukuPermission() },
                            onToggleEngine = { enabled ->
                                if (enabled) {
                                    viewModel.prepareAndStartEngine(onLaunchMediaProjection)
                                } else {
                                    viewModel.stopRoutingEngine()
                                }
                            },
                            modifier = Modifier.animateItem(
                                fadeInSpec = tween(300),
                                placementSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessLow
                                )
                            )
                        )
                    }

                    // Section 1 Header: Active Connected Outputs
                    item(key = "section_header_outputs") {
                        SectionHeaderRow(
                            title = "Active Connected Outputs",
                            subtitle = "${uiState.connectedOutputs.size} Connected Hardware Sink(s) • Live AudioDeviceCallback",
                            badgeText = if (uiState.dualBluetoothShareEnabled) "DUAL SHARE ACTIVE" else "SINGLE SINK"
                        )
                    }

                    // 3. Section 1: Active Connected Outputs (Strictly connected hardware with animateItem)
                    items(
                        items = uiState.connectedOutputs,
                        key = { device -> "output_${device.id}_${device.address}" }
                    ) { device ->
                        ConnectedOutputMixerCard(
                            device = device,
                            onToggleDualShare = { viewModel.toggleDualShareDevice(device.id) },
                            onVolumeChange = { vol -> viewModel.setDeviceVolume(device.id, vol) },
                            onToggleMute = { viewModel.toggleDeviceMute(device.id) },
                            onSelectChannelMode = { mode ->
                                viewModel.setDeviceChannelMode(device.id, mode)
                            },
                            modifier = Modifier.animateItem(
                                fadeInSpec = tween(300),
                                placementSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessLow
                                )
                            )
                        )
                    }

                    // Section 2 Header: Per-App Audio Router
                    item(key = "section_header_apps") {
                        Spacer(modifier = Modifier.height(4.dp))
                        SectionHeaderRow(
                            title = "Per-App Audio Router",
                            subtitle = "Route individual app UIDs & lock TAKE_AUDIO_FOCUS via Shizuku",
                            badgeText = "${uiState.activeStreams.size} STREAM(S)",
                            actionLabel = "Pick App",
                            onActionClick = { showAppPickerSheet = true }
                        )
                    }

                    // 4. Section 2: Per-App Audio Router (LazyColumn items with Modifier.animateItem())
                    items(
                        items = uiState.activeStreams,
                        key = { stream -> "stream_${stream.uid}_${stream.packageName}" }
                    ) { stream ->
                        PerAppRouterCard(
                            stream = stream,
                            connectedOutputs = uiState.connectedOutputs,
                            onRouteToDevice = { targetDeviceId ->
                                viewModel.routeAppToDevice(stream.uid, targetDeviceId)
                            },
                            onAppVolumeChange = { vol ->
                                viewModel.setAppVolume(stream.uid, vol)
                            },
                            onToggleFocusLock = { locked ->
                                viewModel.toggleAppFocusLock(stream.packageName, locked)
                            },
                            modifier = Modifier.animateItem(
                                fadeInSpec = tween(300),
                                placementSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessLow
                                )
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EngineAndShizukuControlCard(
    shizukuStatus: ShizukuStatus,
    silentBypassActive: Boolean,
    isEngineRunning: Boolean,
    telemetry: DspTelemetry,
    onGrantShizuku: () -> Unit,
    onToggleEngine: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor by animateColorAsState(
        targetValue = if (isEngineRunning) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "engine_card_color"
    )

    val cornerRadius by animateDpAsState(
        targetValue = if (isEngineRunning) 16.dp else 28.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "engine_card_radius"
    )

    val borderColor by animateColorAsState(
        targetValue = if (isEngineRunning) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
        },
        animationSpec = tween(300),
        label = "engine_border_color"
    )

    ElevatedCard(
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = if (isEngineRunning) 1.5.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(cornerRadius)
            )
            .animateContentSize()
            .testTag("master_engine_shizuku_card"),
        shape = RoundedCornerShape(cornerRadius),
        colors = CardDefaults.elevatedCardColors(containerColor = containerColor)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(46.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.GraphicEq,
                                contentDescription = "AudioMatrix Engine",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Master Audio Matrix Engine",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (isEngineRunning) {
                                "${telemetry.sampleRateHz}Hz PCM_16BIT • Zero Video Capture"
                            } else {
                                "Silent PROJECT_MEDIA pre-grant • 48kHz FastMixer"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Switch(
                    checked = isEngineRunning,
                    onCheckedChange = onToggleEngine,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("master_engine_switch")
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val isShizukuReady = shizukuStatus == ShizukuStatus.BINDER_ALIVE_GRANTED

                Surface(
                    shape = RoundedCornerShape(50),
                    color = when (shizukuStatus) {
                        ShizukuStatus.BINDER_ALIVE_GRANTED -> MaterialTheme.colorScheme.tertiaryContainer
                        ShizukuStatus.BINDER_ALIVE_DENIED -> MaterialTheme.colorScheme.secondaryContainer
                        ShizukuStatus.BINDER_DEAD_FALLBACK -> MaterialTheme.colorScheme.surfaceVariant
                    },
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = "Shizuku Status",
                            tint = when (shizukuStatus) {
                                ShizukuStatus.BINDER_ALIVE_GRANTED -> MaterialTheme.colorScheme.onTertiaryContainer
                                ShizukuStatus.BINDER_ALIVE_DENIED -> MaterialTheme.colorScheme.onSecondaryContainer
                                ShizukuStatus.BINDER_DEAD_FALLBACK -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = when (shizukuStatus) {
                                ShizukuStatus.BINDER_ALIVE_GRANTED ->
                                    "Shizuku ADB Linked (UID 2000) • 0ms HAL Ready"
                                ShizukuStatus.BINDER_ALIVE_DENIED ->
                                    "Shizuku Detected • Tap Grant Shizuku to Authorize"
                                ShizukuStatus.BINDER_DEAD_FALLBACK ->
                                    "Shizuku ADB Not Running • Tap to Open / Bind"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = when (shizukuStatus) {
                                ShizukuStatus.BINDER_ALIVE_GRANTED -> MaterialTheme.colorScheme.onTertiaryContainer
                                ShizukuStatus.BINDER_ALIVE_DENIED -> MaterialTheme.colorScheme.onSecondaryContainer
                                ShizukuStatus.BINDER_DEAD_FALLBACK -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                FilledTonalButton(
                    onClick = onGrantShizuku,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("grant_shizuku_button")
                ) {
                    Text(
                        text = if (isShizukuReady) {
                            "Linked ✓"
                        } else {
                            "Grant Shizuku"
                        },
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectedOutputMixerCard(
    device: OutputDevice,
    onToggleDualShare: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onToggleMute: () -> Unit,
    onSelectChannelMode: (ChannelMode) -> Unit,
    modifier: Modifier = Modifier
) {
    var expandChannelSplit by rememberSaveable(device.id) { mutableStateOf(true) }

    val cardContainerColor by animateColorAsState(
        targetValue = if (device.isEnabledForDualShare) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "output_card_color"
    )

    val cardCornerRadius by animateDpAsState(
        targetValue = if (device.isEnabledForDualShare) 16.dp else 28.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "output_card_corner"
    )

    val borderGlowColor by animateColorAsState(
        targetValue = if (device.isEnabledForDualShare) {
            MaterialTheme.colorScheme.primary
        } else {
            Color.Transparent
        },
        animationSpec = tween(250),
        label = "output_border_glow"
    )

    val sliderValue = if (device.isMuted) 0f else device.volume

    ElevatedCard(
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = if (device.isEnabledForDualShare) 2.dp else 0.dp,
                color = borderGlowColor,
                shape = RoundedCornerShape(cardCornerRadius)
            )
            .animateContentSize()
            .testTag("connected_output_card_${device.id}"),
        shape = RoundedCornerShape(cardCornerRadius),
        colors = CardDefaults.elevatedCardColors(containerColor = cardContainerColor)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (device.isEnabledForDualShare) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    },
                    modifier = Modifier.size(46.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = when {
                                device.isBluetooth -> Icons.Default.BluetoothAudio
                                device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ->
                                    Icons.Default.Speaker
                                else -> Icons.Default.Headphones
                            },
                            contentDescription = device.typeLabel,
                            tint = if (device.isEnabledForDualShare) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = device.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${device.typeLabel} • ${device.address}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                FilterChip(
                    selected = device.isEnabledForDualShare,
                    onClick = onToggleDualShare,
                    label = { Text("Share Audio") },
                    leadingIcon = {
                        Icon(
                            imageVector = if (device.isEnabledForDualShare) {
                                Icons.Default.Check
                            } else {
                                Icons.Default.Share
                            },
                            contentDescription = "Toggle Share Audio",
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("share_audio_chip_${device.id}")
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalIconButton(
                    onClick = onToggleMute,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("mute_icon_button_${device.id}")
                ) {
                    AnimatedContent(
                        targetState = device.isMuted,
                        transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(180)) },
                        label = "mute_icon_anim"
                    ) { muted ->
                        Icon(
                            imageVector = if (muted) {
                                Icons.AutoMirrored.Filled.VolumeOff
                            } else {
                                Icons.AutoMirrored.Filled.VolumeUp
                            },
                            contentDescription = if (muted) "Unmute" else "Mute",
                            tint = if (muted) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.width(10.dp))

                Slider(
                    value = sliderValue,
                    onValueChange = onVolumeChange,
                    valueRange = 0f..1f,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("device_volume_slider_${device.id}")
                )

                Spacer(modifier = Modifier.width(10.dp))

                val percentageInt = if (device.isMuted) 0 else (device.volume * 100).roundToInt()
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    AnimatedContent(
                        targetState = percentageInt,
                        transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(150)) },
                        label = "volume_pct_anim"
                    ) { pct ->
                        Text(
                            text = "$pct%",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (device.isMuted) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }

                IconButton(
                    onClick = { expandChannelSplit = !expandChannelSplit },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("toggle_channel_split_panel_${device.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = "Toggle Channel Split Controls",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            AnimatedVisibility(
                visible = expandChannelSplit,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    Text(
                        text = "PCM Stereo Channel Split",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    val modes = listOf(
                        ChannelMode.STEREO to "Stereo (L+R)",
                        ChannelMode.LEFT_ONLY to "Left Only",
                        ChannelMode.RIGHT_ONLY to "Right Only"
                    )
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("segmented_channel_row_${device.id}")
                    ) {
                        modes.forEachIndexed { index, (mode, label) ->
                            SegmentedButton(
                                selected = device.channelMode == mode,
                                onClick = { onSelectChannelMode(mode) },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = modes.size
                                ),
                                label = {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1
                                    )
                                },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("segment_${device.id}_${mode.name.lowercase()}")
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PerAppRouterCard(
    stream: ActiveAppStream,
    connectedOutputs: List<OutputDevice>,
    onRouteToDevice: (Int?) -> Unit,
    onAppVolumeChange: (Float) -> Unit,
    onToggleFocusLock: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var dropdownExpanded by remember { mutableStateOf(false) }

    val animatedAppVolume by animateFloatAsState(
        targetValue = stream.volume,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "app_slider_float"
    )

    val focusLockContainerColor by animateColorAsState(
        targetValue = if (stream.focusLocked) {
            MaterialTheme.colorScheme.tertiaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "focus_lock_container_color"
    )

    val focusLockCornerRadius by animateDpAsState(
        targetValue = if (stream.focusLocked) 16.dp else 28.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "focus_lock_corner_radius"
    )

    val selectedOutputLabel = when (stream.assignedDeviceId) {
        AudioMatrixViewModel.DUAL_SHARE_MIX_DEVICE_ID -> "Dual Share Mix (Simultaneous)"
        null -> "System Default Routing (Auto)"
        else -> connectedOutputs.firstOrNull { it.id == stream.assignedDeviceId }?.name
            ?: connectedOutputs.firstOrNull()?.name
            ?: "Phone Stereo Speaker"
    }

    OutlinedCard(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize()
            .testTag("per_app_router_card_${stream.uid}"),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (stream.focusLocked) {
                MaterialTheme.colorScheme.tertiary.copy(alpha = 0.65f)
            } else {
                MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stream.appName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stream.packageName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = "UID ${stream.uid}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            ExposedDropdownMenuBox(
                expanded = dropdownExpanded,
                onExpandedChange = { dropdownExpanded = !dropdownExpanded },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("output_dropdown_box_${stream.uid}")
            ) {
                OutlinedTextField(
                    value = selectedOutputLabel,
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    label = { Text("Target Output Sink (0ms IAudioService)") },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded)
                    },
                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth()
                )

                ExposedDropdownMenu(
                    expanded = dropdownExpanded,
                    onDismissRequest = { dropdownExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    text = "System Default Routing (Auto)",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Clear custom UID affinity & use standard Android output",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        onClick = {
                            onRouteToDevice(null)
                            dropdownExpanded = false
                        }
                    )

                    connectedOutputs.forEach { output ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        text = output.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "${output.typeLabel} • ${output.channelMode.name} (0ms Binder Route)",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            onClick = {
                                onRouteToDevice(output.id)
                                dropdownExpanded = false
                            }
                        )
                    }

                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    text = "Dual Share Mix (Simultaneous)",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Broadcast stream to all Share Audio outputs",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        onClick = {
                            onRouteToDevice(AudioMatrixViewModel.DUAL_SHARE_MIX_DEVICE_ID)
                            dropdownExpanded = false
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "APP GAIN",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(64.dp)
                )
                Slider(
                    value = stream.volume,
                    onValueChange = onAppVolumeChange,
                    valueRange = 0f..1f,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("app_volume_slider_${stream.uid}")
                )
                Spacer(modifier = Modifier.width(10.dp))
                val appPct = (stream.volume * 100).roundToInt()
                AnimatedContent(
                    targetState = appPct,
                    transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(150)) },
                    label = "app_vol_pct"
                ) { pct ->
                    Text(
                        text = "$pct%",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(42.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Surface(
                shape = RoundedCornerShape(focusLockCornerRadius),
                color = focusLockContainerColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(focusLockCornerRadius))
                    .clickable { onToggleFocusLock(!stream.focusLocked) }
                    .testTag("focus_lock_bar_${stream.uid}")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (stream.focusLocked) {
                                Icons.Default.Lock
                            } else {
                                Icons.Default.LockOpen
                            },
                            contentDescription = "Audio Focus Lock",
                            tint = if (stream.focusLocked) {
                                MaterialTheme.colorScheme.onTertiaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Lock Audio Focus (Play Simultaneously)",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (stream.focusLocked) {
                                    MaterialTheme.colorScheme.onTertiaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                            Text(
                                text = if (stream.focusLocked) {
                                    "TAKE_AUDIO_FOCUS ignore • Never pauses or ducks"
                                } else {
                                    "TAKE_AUDIO_FOCUS allow • Standard Android focus"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (stream.focusLocked) {
                                    MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.85f)
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }

                    Switch(
                        checked = stream.focusLocked,
                        onCheckedChange = onToggleFocusLock,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("focus_lock_switch_${stream.uid}")
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeaderRow(
    title: String,
    subtitle: String,
    badgeText: String,
    actionLabel: String? = null,
    onActionClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (actionLabel != null && onActionClick != null) {
                FilledTonalButton(
                    onClick = onActionClick,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("pick_installed_app_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = actionLabel,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = actionLabel,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Text(
                    text = badgeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
        }
    }
}

@Composable
private fun InstalledAppPickerDialog(
    installedApps: List<ActiveAppStream>,
    onDismiss: () -> Unit,
    onSelectApp: (ActiveAppStream) -> Unit
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val filteredApps = remember(installedApps, searchQuery) {
        if (searchQuery.isBlank()) {
            installedApps
        } else {
            installedApps.filter {
                it.appName.contains(searchQuery, ignoreCase = true) ||
                    it.packageName.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Add Installed App to Router",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(380.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    singleLine = true,
                    label = { Text("Search app name or package") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("app_picker_search_input")
                )
                Spacer(modifier = Modifier.height(10.dp))
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(
                        items = filteredApps,
                        key = { "${it.uid}_${it.packageName}" }
                    ) { app ->
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { onSelectApp(app) }
                                .testTag("pick_app_item_${app.packageName}")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = app.appName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = app.packageName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = MaterialTheme.colorScheme.secondaryContainer
                                ) {
                                    Text(
                                        text = "UID ${app.uid}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

