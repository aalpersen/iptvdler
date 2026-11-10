package io.github.aalpersen.iptvdler.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.aalpersen.iptvdler.i18n.strings

/** Scaffold with a top app bar shared by all screens. [onBack] null hides the back arrow. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun IptvdlerScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable (Modifier) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.back)
                        }
                    }
                },
                actions = actions,
            )
        },
        floatingActionButton = floatingActionButton,
    ) { padding ->
        content(Modifier.padding(padding).fillMaxSize())
    }
}

@Composable
internal fun EmptyState(title: String, message: String, modifier: Modifier = Modifier) {
    Box(modifier.padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * A message cut to [maxLines] (errors in list rows). When [details] is set, tapping it opens a dialog with
 * the full text, which can be selected and copied (e.g. a redirect address to report).
 */
@Composable
internal fun DetailsText(
    text: String,
    details: String?,
    color: Color,
    maxLines: Int = 2,
    style: TextStyle = LocalTextStyle.current,
) {
    var open by remember { mutableStateOf(false) }
    Text(
        text,
        color = color,
        style = style,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = if (details != null) Modifier.clickable { open = true } else Modifier,
    )
    if (open && details != null) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(strings.errorDetails) },
            text = { SelectionContainer { Text(details) } },
            confirmButton = { TextButton(onClick = { open = false }) { Text(strings.close) } },
        )
    }
}
