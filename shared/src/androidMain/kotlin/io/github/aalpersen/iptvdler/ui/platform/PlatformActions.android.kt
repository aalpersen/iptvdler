package io.github.aalpersen.iptvdler.ui.platform

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import io.github.aalpersen.iptvdler.i18n.strings
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Copies a finished file to a place the user picks (Downloads, SD card, cloud...) via the system file picker. */
@Composable
actual fun rememberDownloadFileAction(): DownloadFileAction {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingSource by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val source = pendingSource
        pendingSource = null
        if (uri == null || source == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out -> File(source).inputStream().use { it.copyTo(out) } }
                        ?: error("Can't write there")
                }.isSuccess
            }
            Toast.makeText(context, if (ok) strings.exported else strings.exportFailed, Toast.LENGTH_SHORT).show()
        }
    }
    return remember(launcher, strings) {
        DownloadFileAction(strings.exportFile) { path, name ->
            pendingSource = path
            launcher.launch(name)
        }
    }
}

@Composable
actual fun rememberNotificationPermissionRequest(): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return remember(launcher) {
        {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

@Composable
actual fun rememberFolderPicker(onPicked: (String) -> Unit): (() -> Unit)? = null

actual val supportsWifiOnlySetting: Boolean = true

@Composable
actual fun rememberOpenLogFolder(): (() -> Unit)? = null
