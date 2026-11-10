package io.github.aalpersen.iptvdler.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/**
 * A ViewModel scope that lives exactly as long as the calling composable (e.g. a dialog), so each
 * time the dialog opens it gets a fresh ViewModel instead of the screen-scoped one from last time.
 */
@Composable
fun rememberComposableViewModelStoreOwner(): ViewModelStoreOwner {
    val owner = remember {
        object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
    }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    return owner
}
