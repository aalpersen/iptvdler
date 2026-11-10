package io.github.aalpersen.iptvdler.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import io.github.aalpersen.iptvdler.data.settings.AppSettings
import io.github.aalpersen.iptvdler.i18n.strings
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun DisclaimerText() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        strings.disclaimerPoints.forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
    }
}

class DisclaimerViewModel(private val settings: AppSettings) : ViewModel() {
    fun accept() = settings.acceptDisclaimer()
}

@Composable
fun DisclaimerScreen(
    onAccepted: () -> Unit,
    viewModel: DisclaimerViewModel = koinViewModel(),
) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(strings.welcome, style = MaterialTheme.typography.headlineMedium)
                DisclaimerText()
                Button(
                    onClick = {
                        viewModel.accept()
                        onAccepted()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(strings.iUnderstand)
                }
            }
        }
    }
}
