package io.github.aalpersen.iptvdler.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.github.aalpersen.iptvdler.data.settings.AppSettings
import io.github.aalpersen.iptvdler.ui.channels.ChannelsScreen
import io.github.aalpersen.iptvdler.ui.channels.GroupsScreen
import io.github.aalpersen.iptvdler.ui.channels.SeriesScreen
import io.github.aalpersen.iptvdler.ui.navigation.ChannelsRoute
import io.github.aalpersen.iptvdler.ui.navigation.DisclaimerRoute
import io.github.aalpersen.iptvdler.ui.navigation.DownloadsRoute
import io.github.aalpersen.iptvdler.ui.navigation.GroupsRoute
import io.github.aalpersen.iptvdler.ui.navigation.PlayerRoute
import io.github.aalpersen.iptvdler.ui.navigation.PlaylistsRoute
import io.github.aalpersen.iptvdler.ui.navigation.SeriesRoute
import io.github.aalpersen.iptvdler.ui.navigation.SettingsRoute
import io.github.aalpersen.iptvdler.ui.player.PlayerScreen
import io.github.aalpersen.iptvdler.ui.playlists.PlaylistsScreen
import io.github.aalpersen.iptvdler.ui.screens.DisclaimerScreen
import io.github.aalpersen.iptvdler.ui.downloads.DownloadsScreen
import io.github.aalpersen.iptvdler.ui.settings.SettingsScreen
import io.github.aalpersen.iptvdler.ui.theme.IptvdlerTheme
import org.koin.compose.koinInject

@Composable
fun App() {
    IptvdlerTheme {
        val navController = rememberNavController()
        val settings = koinInject<AppSettings>()
        // Read once: after acceptance the disclaimer is popped off the back stack.
        val startDestination: Any = remember {
            if (settings.disclaimerAccepted.value) PlaylistsRoute else DisclaimerRoute
        }

        NavHost(navController = navController, startDestination = startDestination) {
            composable<DisclaimerRoute> {
                DisclaimerScreen(
                    onAccepted = {
                        navController.navigate(PlaylistsRoute) {
                            popUpTo<DisclaimerRoute> { inclusive = true }
                        }
                    },
                )
            }
            composable<PlaylistsRoute> {
                PlaylistsScreen(
                    onOpenPlaylist = { navController.navigate(GroupsRoute(it)) },
                    onOpenDownloads = { navController.navigate(DownloadsRoute) },
                    onOpenSettings = { navController.navigate(SettingsRoute) },
                )
            }
            composable<GroupsRoute> { entry ->
                GroupsScreen(
                    route = entry.toRoute<GroupsRoute>(),
                    onOpenChannels = { navController.navigate(it) },
                    onBack = navController::navigateUp,
                )
            }
            composable<ChannelsRoute> { entry ->
                ChannelsScreen(
                    route = entry.toRoute<ChannelsRoute>(),
                    onPlay = { channel ->
                        val seriesId = channel.seriesId
                        if (seriesId != null) {
                            navController.navigate(SeriesRoute(channel.playlistId, seriesId, channel.name))
                        } else {
                            navController.navigate(PlayerRoute(channelId = channel.id))
                        }
                    },
                    onBack = navController::navigateUp,
                    onOpenDownloads = { navController.navigate(DownloadsRoute) },
                )
            }
            composable<SeriesRoute> { entry ->
                SeriesScreen(
                    route = entry.toRoute<SeriesRoute>(),
                    onPlay = { navController.navigate(it) },
                    onBack = navController::navigateUp,
                    onOpenDownloads = { navController.navigate(DownloadsRoute) },
                )
            }
            composable<PlayerRoute> { entry ->
                PlayerScreen(
                    route = entry.toRoute<PlayerRoute>(),
                    onBack = navController::navigateUp,
                    onOpenDownloads = { navController.navigate(DownloadsRoute) },
                )
            }
            composable<DownloadsRoute> {
                DownloadsScreen(
                    onBack = navController::navigateUp,
                    onPlay = { id -> navController.navigate(PlayerRoute(downloadId = id)) },
                )
            }
            composable<SettingsRoute> {
                SettingsScreen(onBack = navController::navigateUp)
            }
        }
    }
}
