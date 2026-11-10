package io.github.aalpersen.iptvdler.ui.playlists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.aalpersen.iptvdler.core.model.Playlist
import io.github.aalpersen.iptvdler.core.model.PlaylistSourceType
import io.github.aalpersen.iptvdler.data.playlist.PlaylistInput
import io.github.aalpersen.iptvdler.data.xtream.XtreamLink
import io.github.aalpersen.iptvdler.i18n.strings
import io.github.aalpersen.iptvdler.ui.platform.PickedFile
import io.github.aalpersen.iptvdler.ui.platform.rememberPlaylistFilePicker
import io.github.aalpersen.iptvdler.ui.player.PlayerIcons

private val refreshIntervals: List<Pair<Long?, String>>
    get() = listOf(
        null to strings.manualOnly,
        6 * 60L to strings.every6Hours,
        12 * 60L to strings.every12Hours,
        24 * 60L to strings.everyDay,
        3 * 24 * 60L to strings.every3Days,
        7 * 24 * 60L to strings.everyWeek,
    )

private val sourceTypes = listOf(PlaylistSourceType.URL, PlaylistSourceType.FILE, PlaylistSourceType.XTREAM)

@Composable
internal fun PlaylistDialog(
    initial: Playlist?,
    onDismiss: () -> Unit,
    onConfirm: (PlaylistInput) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var sourceType by remember { mutableStateOf(initial?.sourceType ?: PlaylistSourceType.URL) }
    var url by remember {
        mutableStateOf(if (initial?.sourceType == PlaylistSourceType.URL) initial.source else "")
    }
    var file by remember {
        mutableStateOf(
            if (initial?.sourceType == PlaylistSourceType.FILE) {
                PickedFile(initial.source, initial.source.substringAfterLast('/').substringAfterLast('\\'))
            } else {
                null
            },
        )
    }
    val isXtream = initial?.sourceType == PlaylistSourceType.XTREAM
    var server by remember { mutableStateOf(if (isXtream) initial.source else "") }
    var username by remember { mutableStateOf(if (isXtream) initial.xtreamUsername.orEmpty() else "") }
    var password by remember { mutableStateOf(if (isXtream) initial.xtreamPassword.orEmpty() else "") }
    var interval by remember { mutableStateOf(initial?.refreshIntervalMinutes) }

    val pickFile = rememberPlaylistFilePicker { picked ->
        if (picked != null) {
            file = picked
            if (name.isBlank()) name = picked.displayName.substringBeforeLast('.')
        }
    }

    /** A pasted Xtream link fills in server, username and password (and a name if there's none). */
    fun useXtreamLink(text: String): Boolean {
        val credentials = XtreamLink.parse(text) ?: return false
        sourceType = PlaylistSourceType.XTREAM
        server = credentials.server
        username = credentials.username
        password = credentials.password
        if (name.isBlank()) name = credentials.server.substringAfter("://").substringBefore(':').substringBefore('/')
        return true
    }

    val trimmedUrl = url.trim()
    val urlValid = trimmedUrl.startsWith("http://", ignoreCase = true) || trimmedUrl.startsWith("https://", ignoreCase = true)
    val normalizedServer = XtreamLink.normalizeServer(server)
    val input = when (sourceType) {
        PlaylistSourceType.URL -> PlaylistInput(name.trim(), trimmedUrl, sourceType, interval).takeIf { urlValid }
        PlaylistSourceType.FILE -> file?.let { PlaylistInput(name.trim(), it.location, sourceType, interval) }
        PlaylistSourceType.XTREAM -> normalizedServer?.takeIf { username.isNotBlank() && password.isNotEmpty() }?.let {
            PlaylistInput(name.trim(), it, sourceType, interval, xtreamUsername = username.trim(), xtreamPassword = password)
        }
    }?.takeIf { name.isNotBlank() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) strings.addPlaylist else strings.editPlaylist) },
        text = {
            Column(
                Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(strings.name) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    sourceTypes.forEachIndexed { index, type ->
                        SegmentedButton(
                            selected = sourceType == type,
                            onClick = { sourceType = type },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = sourceTypes.size),
                        ) {
                            Text(
                                when (type) {
                                    PlaylistSourceType.URL -> strings.url
                                    PlaylistSourceType.FILE -> strings.file
                                    PlaylistSourceType.XTREAM -> strings.xtream
                                },
                            )
                        }
                    }
                }
                when (sourceType) {
                    PlaylistSourceType.URL -> {
                        OutlinedTextField(
                            value = url,
                            onValueChange = { url = it },
                            label = { Text(strings.m3uUrl) },
                            placeholder = { Text("https://…") },
                            isError = url.isNotBlank() && !urlValid,
                            supportingText = if (url.isNotBlank() && !urlValid) {
                                { Text(strings.urlInvalid) }
                            } else {
                                null
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        // An M3U link from an Xtream panel: offer the richer API login instead.
                        if (XtreamLink.isXtreamM3uLink(trimmedUrl)) {
                            Text(strings.xtreamLinkDetected, style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = { useXtreamLink(trimmedUrl) }) { Text(strings.useAsXtream) }
                        }
                    }
                    PlaylistSourceType.FILE -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = pickFile) { Text(if (file == null) strings.chooseFile else strings.changeFile) }
                        file?.let {
                            Text(
                                it.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    PlaylistSourceType.XTREAM -> XtreamFields(
                        server = server,
                        // Only for pastes (many characters at once): typing a link by hand would otherwise
                        // fill in the fields as soon as "password=" has its first character.
                        onServerChange = { if (it.length - server.length <= 1 || !useXtreamLink(it)) server = it },
                        serverValid = server.isBlank() || normalizedServer != null,
                        username = username,
                        onUsernameChange = { username = it },
                        password = password,
                        onPasswordChange = { password = it },
                    )
                }
                RefreshIntervalPicker(interval, onSelect = { interval = it })
            }
        },
        confirmButton = {
            TextButton(
                enabled = input != null,
                onClick = { input?.let(onConfirm) },
            ) { Text(if (initial == null) strings.add else strings.save) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.cancel) } },
    )
}

@Composable
private fun XtreamFields(
    server: String,
    onServerChange: (String) -> Unit,
    serverValid: Boolean,
    username: String,
    onUsernameChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
) {
    var showPassword by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = server,
        onValueChange = onServerChange,
        label = { Text(strings.xtreamServer) },
        placeholder = { Text("http://example.com:8080") },
        isError = !serverValid,
        supportingText = { Text(if (serverValid) strings.xtreamPasteHint else strings.xtreamServerInvalid) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = username,
        onValueChange = onUsernameChange,
        label = { Text(strings.username) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = password,
        onValueChange = onPasswordChange,
        label = { Text(strings.password) },
        singleLine = true,
        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { showPassword = !showPassword }) {
                Icon(
                    if (showPassword) PlayerIcons.VisibilityOff else PlayerIcons.Visibility,
                    contentDescription = if (showPassword) strings.hidePassword else strings.showPassword,
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun RefreshIntervalPicker(selected: Long?, onSelect: (Long?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = refreshIntervals.firstOrNull { it.first == selected }?.second ?: strings.everyMinutes(selected ?: 0)
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(strings.autoRefresh(label))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            refreshIntervals.forEach { (minutes, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = {
                    onSelect(minutes)
                    expanded = false
                })
            }
        }
    }
}
