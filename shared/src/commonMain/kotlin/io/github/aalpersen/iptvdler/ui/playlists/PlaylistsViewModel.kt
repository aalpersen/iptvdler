package io.github.aalpersen.iptvdler.ui.playlists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.aalpersen.iptvdler.core.model.EpochClock
import io.github.aalpersen.iptvdler.core.model.Playlist
import io.github.aalpersen.iptvdler.data.playlist.PlaylistInput
import io.github.aalpersen.iptvdler.data.playlist.PlaylistRefresher
import io.github.aalpersen.iptvdler.data.playlist.PlaylistRepository
import io.github.aalpersen.iptvdler.data.update.AvailableUpdate
import io.github.aalpersen.iptvdler.data.update.UpdateChecker
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PlaylistsViewModel(
    private val repository: PlaylistRepository,
    private val refresher: PlaylistRefresher,
    private val clock: EpochClock,
    private val updateChecker: UpdateChecker,
) : ViewModel() {

    fun nowMillis(): Long = clock.nowMillis()

    /** A newer GitHub release to point the user to, if any. */
    val availableUpdate: StateFlow<AvailableUpdate?> = updateChecker.available
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun dismissUpdate(update: AvailableUpdate) = updateChecker.dismiss(update)

    /** null while the first load is pending. */
    val playlists: StateFlow<List<Playlist>?> = repository.observePlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val refreshProgress: StateFlow<Map<Long, Int>> = refresher.progress

    fun add(input: PlaylistInput) {
        viewModelScope.launch {
            val id = repository.addPlaylist(input)
            refresher.refresh(id)
        }
    }

    fun update(playlist: Playlist, input: PlaylistInput) {
        viewModelScope.launch {
            repository.updatePlaylist(playlist.id, input)
            if (input.source != playlist.source || input.sourceType != playlist.sourceType) {
                refresher.cancel(playlist.id)
                refresher.refresh(playlist.id)
            }
        }
    }

    fun refresh(id: Long) {
        refresher.refresh(id)
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            refresher.cancel(id)
            repository.deletePlaylist(id)
        }
    }
}
