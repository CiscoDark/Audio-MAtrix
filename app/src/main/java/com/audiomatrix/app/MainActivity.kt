package com.audiomatrix.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val onShizukuGrantedCallback: () -> Unit = {
        lifecycleScope.launch {
            ShizukuAudioController.autoGrantSilentCapturePermissions(packageName)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        ShizukuAudioController.initializeListeners(this, onShizukuGrantedCallback)
        if (ShizukuAudioController.isShizukuReady()) {
            lifecycleScope.launch {
                ShizukuAudioController.autoGrantSilentCapturePermissions(packageName)
            }
        }

        setContent {
            AudioMatrixAppHost(activity = this)
        }
    }

    override fun onDestroy() {
        ShizukuAudioController.cleanupListeners(onShizukuGrantedCallback)
        super.onDestroy()
    }
}

@Composable
fun AudioMatrixAppHost(
    activity: ComponentActivity,
    viewModel: AudioMatrixViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    DisposableEffect(activity) {
        val callback: () -> Unit = {
            ShizukuAudioController.autoGrantSilentCapturePermissionsAsync(activity.packageName)
        }
        ShizukuAudioController.initializeListeners(activity, callback)
        viewModel.refreshRuntimePermissions()
        onDispose {
            ShizukuAudioController.cleanupListeners(callback)
        }
    }

    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { activityResult ->
        viewModel.onProjectionResult(
            resultCode = activityResult.resultCode,
            data = activityResult.data
        )
    }

    val projectionPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refreshRuntimePermissions()
        val projectionManager =
            activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    val settingsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refreshRuntimePermissions()
    }

    AudioMatrixTheme(settings = uiState.settings) {
        MixerDashboardScreen(
            viewModel = viewModel,
            onLaunchMediaProjection = {
                val projectionManager = activity.getSystemService(
                    Context.MEDIA_PROJECTION_SERVICE
                ) as MediaProjectionManager

                if (ShizukuAudioController.isShizukuReady()) {
                    mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
                } else {
                    val requiredPermissions = buildRequiredPermissionsList()
                    val missing = requiredPermissions.filter { perm ->
                        ContextCompat.checkSelfPermission(activity, perm) !=
                            PackageManager.PERMISSION_GRANTED
                    }
                    if (missing.isNotEmpty()) {
                        projectionPermissionLauncher.launch(missing.toTypedArray())
                    } else {
                        mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
                    }
                }
            },
            onRequestSinglePermission = { permission ->
                settingsPermissionLauncher.launch(arrayOf(permission))
            },
            onRequestAllPermissions = {
                val perms = buildRequiredPermissionsList()
                settingsPermissionLauncher.launch(perms.toTypedArray())
            },
            onOpenSystemAppSettings = {
                runCatching {
                    val intent = Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", activity.packageName, null)
                    ).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    activity.startActivity(intent)
                }
            }
        )
    }
}

private fun buildRequiredPermissionsList(): List<String> {
    val list = mutableListOf(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        list.add(Manifest.permission.BLUETOOTH_CONNECT)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        list.add(Manifest.permission.POST_NOTIFICATIONS)
    }
    return list
}
