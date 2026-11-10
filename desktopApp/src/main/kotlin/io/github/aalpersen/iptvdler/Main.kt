package io.github.aalpersen.iptvdler

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.LocalWindowExceptionHandlerFactory
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowExceptionHandler
import androidx.compose.ui.window.WindowExceptionHandlerFactory
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.aalpersen.iptvdler.di.initKoin
import io.github.aalpersen.iptvdler.i18n.AppLanguage
import io.github.aalpersen.iptvdler.i18n.I18n
import io.github.aalpersen.iptvdler.i18n.strings
import io.github.aalpersen.iptvdler.platform.CrashReporter
import io.github.aalpersen.iptvdler.platform.SingleInstance
import io.github.aalpersen.iptvdler.player.useUtf8ForNativeStrings
import io.github.aalpersen.iptvdler.ui.App
import io.github.aalpersen.iptvdler.ui.platform.LocalPlayerHost
import io.github.aalpersen.iptvdler.ui.platform.QuitConfirmationDialog
import io.github.aalpersen.iptvdler.ui.platform.QuitGuard
import io.github.aalpersen.iptvdler.ui.platform.WindowPlayerHost
import javax.imageio.ImageIO
import javax.swing.JOptionPane
import kotlinx.coroutines.launch

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    CrashReporter.install()
    useUtf8ForNativeStrings()
    // Before Koin loads the saved choice: the system language for this one early message.
    I18n.apply(AppLanguage.SYSTEM)
    if (!SingleInstance.acquire()) {
        JOptionPane.showMessageDialog(null, strings.alreadyRunning, "iptvdler", JOptionPane.INFORMATION_MESSAGE)
        return
    }
    val koin = initKoin().koin
    val quitGuard = koin.get<QuitGuard>()

    application {
        // Errors thrown by the UI are logged like any other crash (instead of Compose's own dialog).
        CompositionLocalProvider(
            LocalWindowExceptionHandlerFactory provides WindowExceptionHandlerFactory {
                WindowExceptionHandler { error -> CrashReporter.report(Thread.currentThread().name, error) }
            },
        ) {
            MainWindow(quitGuard)
        }
    }
}

@Composable
private fun ApplicationScope.MainWindow(quitGuard: QuitGuard) {
    val windowState = rememberWindowState(width = 1100.dp, height = 720.dp)
    val playerHost = remember(windowState) { WindowPlayerHost(windowState) }
    val activeWork by quitGuard.activeWork.collectAsState()
    var confirmQuit by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val icon = remember { appIcon() }

    Window(
        onCloseRequest = { if (activeWork.isEmpty) exitApplication() else confirmQuit = true },
        title = "iptvdler",
        icon = icon,
        state = windowState,
    ) {
        CompositionLocalProvider(LocalPlayerHost provides playerHost) { App() }
        if (confirmQuit) {
            QuitConfirmationDialog(
                work = activeWork,
                onQuit = {
                    scope.launch {
                        quitGuard.prepareToQuit()
                        exitApplication()
                    }
                },
                onCancel = { confirmQuit = false },
            )
        }
    }
}

/** Title bar / taskbar icon (the installers use the icons in desktopApp/icons). */
private fun appIcon(): Painter? = runCatching {
    val image = Thread.currentThread().contextClassLoader.getResourceAsStream("iptvdler_icon.png")!!.use(ImageIO::read)
    BitmapPainter(image.toComposeImageBitmap())
}.getOrNull()
