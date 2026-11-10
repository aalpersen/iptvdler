package io.github.aalpersen.iptvdler.ui.platform

import androidx.compose.runtime.Composable

/** What the platform offers for a finished download file: "Export…" (Android) or "Show in folder" (desktop). */
class DownloadFileAction(val label: String, val run: (path: String, suggestedName: String) -> Unit)

@Composable
expect fun rememberDownloadFileAction(): DownloadFileAction

/** Asks for permission to show download notifications where needed (Android 13+); no-op elsewhere. */
@Composable
expect fun rememberNotificationPermissionRequest(): () -> Unit

/** Folder chooser for the download location, or null where the location is fixed (Android). */
@Composable
expect fun rememberFolderPicker(onPicked: (String) -> Unit): (() -> Unit)?

/** Whether the Wi-Fi-only setting means anything on this platform. */
expect val supportsWifiOnlySetting: Boolean

/** Opens the folder with crash logs (desktop), or null where users can't browse app files (Android). */
@Composable
expect fun rememberOpenLogFolder(): (() -> Unit)?
