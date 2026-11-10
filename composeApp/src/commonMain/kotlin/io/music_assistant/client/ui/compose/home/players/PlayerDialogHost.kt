package io.music_assistant.client.ui.compose.home.players

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.music_assistant.client.data.model.client.Lyrics
import io.music_assistant.client.data.model.client.PlayerData
import io.music_assistant.client.data.model.client.byId
import io.music_assistant.client.data.model.client.items.AppMediaItem
import io.music_assistant.client.data.model.client.items.PodcastEpisode
import io.music_assistant.client.data.model.client.items.Track
import io.music_assistant.client.data.repository.QueueOverlayRepository
import io.music_assistant.client.ui.compose.common.DataState
import io.music_assistant.client.ui.compose.common.action.PlayerAction
import io.music_assistant.client.ui.compose.common.items.AddToPlaylistDialog
import io.music_assistant.client.ui.compose.common.items.ChooseArtistDialog
import io.music_assistant.client.ui.compose.common.items.PlaylistActions
import io.music_assistant.client.ui.compose.home.HomeScreenViewModel
import io.music_assistant.client.ui.compose.home.players.announcement.AnnouncementDialog
import io.music_assistant.client.ui.compose.provider.ProviderViewModel
import io.music_assistant.client.ui.utils.DeviceChrome
import io.music_assistant.client.ui.utils.WindowClass
import kotlinx.coroutines.flow.Flow
import org.koin.compose.koinInject

/**
 * Renders the one open player dialog, outside the pager.
 *
 * Every dialog on the players screen lives here so that removing or reordering a pager page
 * can never tear down an open dialog. The host resolves [request] against the newest
 * [players] on each composition: when the player or the track the request names is gone, it
 * clears the request instead of leaving a stale id behind that a returning player would
 * revive.
 *
 * [homeScreenViewModel] is passed whole rather than as one callback per action: this is a
 * feature composable for a single screen, and the narrow form needs eleven parameters.
 */
@Composable
fun PlayerDialogHost(
    request: PlayerDialogRequest?,
    players: List<PlayerData>,
    homeScreenViewModel: HomeScreenViewModel,
    dspSettingsViewModel: DspSettingsViewModel,
    providerViewModel: ProviderViewModel,
    playlistActions: PlaylistActions?,
    onNavigateToItem: (AppMediaItem) -> Unit,
    onDismiss: () -> Unit,
) {
    request ?: return
    val player = players.byId(request.playerId)?.takeIf { request.hasAnchor(it) }
        ?: return DismissEffect(request, onDismiss)

    when (request) {
        is PlayerDialogRequest.Dsp -> DspSettingsDialog(
            playerId = request.playerId,
            dspSettingsViewModel = dspSettingsViewModel,
            onDismissRequest = onDismiss,
        )

        is PlayerDialogRequest.SleepTimer -> SleepTimerDialog(
            expiresAtSec = player.player.sleepTimerExpiresAt,
            onSelect = { homeScreenViewModel.setSleepTimer(request.playerId, it) },
            onClear = { homeScreenViewModel.clearSleepTimer(request.playerId) },
            onDismissRequest = onDismiss,
        )

        is PlayerDialogRequest.Lyrics -> {
            val showSideQueue = WindowClass.isWide()
            if (pageSplitOrNull(lyricsOpen = true) == null && !showSideQueue) {
                ResolvedLyrics(player, homeScreenViewModel, request, onDismiss) { lyrics, livePositionFlow, kind ->
                    LyricsSheet(
                        lyrics = lyrics,
                        livePositionFlow = livePositionFlow,
                        onDismiss = onDismiss,
                        kind = kind,
                        onSeek = { timeMs ->
                            homeScreenViewModel.playerAction(player, PlayerAction.SeekTo((timeMs / 1000L).coerceAtLeast(0L)))
                        },
                    )
                }
            }
        }

        is PlayerDialogRequest.ChooseArtist -> (player.queueInfo?.currentItem?.track as? Track)?.let {
            ChooseArtistDialog(
                artists = it.artists,
                onSelect = { artist ->
                    onDismiss()
                    onNavigateToItem(artist)
                },
                onDismiss = onDismiss,
            )
        }

        is PlayerDialogRequest.AudioChain -> player.queueInfo?.currentItem?.let { queueTrack ->
            AudioChainDialog(
                queueTrack = queueTrack,
                player = player,
                providerDetails = providerViewModel::getProviderDetails,
                onOpenOrigin = { origin ->
                    onDismiss()
                    onNavigateToItem(origin)
                },
                onDismissRequest = onDismiss,
            )
        }

        is PlayerDialogRequest.PlaybackSpeed -> player.queueInfo?.playbackSpeed?.let { speed ->
            PlaybackSpeedDialog(
                currentSpeed = speed,
                onConfirm = {
                    homeScreenViewModel.playerAction(player, PlayerAction.SetPlaybackSpeed(it))
                },
                onDismissRequest = onDismiss,
            )
        }

        is PlayerDialogRequest.Announcement -> AnnouncementDialog(
            player = player,
            onDismissRequest = onDismiss,
        )

        is PlayerDialogRequest.AmbientSound -> player.queueInfo?.let { queueInfo ->
            queueInfo.overlay?.let { overlay ->
                val repository = koinInject<QueueOverlayRepository>()
                AmbientSoundDialog(
                    overlay = overlay,
                    playerName = player.player.nameAndSuffix,
                    loadSounds = repository::soundEffects,
                    onApply = { uri, volume -> repository.apply(queueInfo.id, uri, volume) },
                    onDismissRequest = onDismiss,
                )
            }
        }

        is PlayerDialogRequest.AddToPlaylist -> playlistActions?.let { actions ->
            AddToPlaylistDialog(
                item = request.item,
                playlistActions = actions,
                onDismiss = onDismiss,
            )
        }
    }
}

/** Clears a request whose anchor is gone, once per request. */
@Composable
private fun DismissEffect(request: PlayerDialogRequest, onDismiss: () -> Unit) {
    LaunchedEffect(request) { onDismiss() }
}

/** Lyrics on the page beside the player, for windows with a [DeviceChrome.pageSplit]. */
@Composable
fun LyricsPage(
    request: PlayerDialogRequest.Lyrics,
    players: List<PlayerData>,
    homeScreenViewModel: HomeScreenViewModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val player = players.byId(request.playerId) ?: return
    ResolvedLyrics(player, homeScreenViewModel, request, onDismiss) { lyrics, livePositionFlow, kind ->
        LyricsPanel(
            lyrics = lyrics,
            livePositionFlow = livePositionFlow,
            onDismiss = onDismiss,
            kind = kind,
            modifier = modifier,
            closeIcon = Icons.Default.Close,
            onSeek = { timeMs ->
                homeScreenViewModel.playerAction(player, PlayerAction.SeekTo((timeMs / 1000L).coerceAtLeast(0L)))
            },
        )
    }
}

@Composable
private fun ResolvedLyrics(
    player: PlayerData,
    homeScreenViewModel: HomeScreenViewModel,
    request: PlayerDialogRequest,
    onDismiss: () -> Unit,
    content: @Composable (Lyrics, Flow<Double>?, LyricsSheetKind) -> Unit,
) {
    val currentMedia = player.player.currentMedia
    val item = player.queueInfo?.currentItem?.track
    if (currentMedia == null && item == null) {
        return DismissEffect(request, onDismiss)
    }

    val currentTrack = item as? Track
    val title = currentTrack?.name ?: currentMedia?.title
    val artist = currentTrack?.artists?.firstOrNull()?.name ?: currentMedia?.artist
    val duration = currentTrack?.duration?.toDouble() ?: currentMedia?.duration ?: 0.0

    val mediaKey = remember(artist, title) {
        if (!title.isNullOrBlank()) "${artist.orEmpty().trim()} - ${title.trim()}".trim() else ""
    }

    LaunchedEffect(mediaKey) {
        if (mediaKey.isNotBlank()) {
            homeScreenViewModel.loadLyrics(title, artist, duration, currentTrack)
        }
    }

    val mediaLyricsState = remember(mediaKey) {
        if (mediaKey.isNotBlank()) homeScreenViewModel.lyrics(mediaKey)
        else kotlinx.coroutines.flow.MutableStateFlow(DataState.NoData())
    }.collectAsStateWithLifecycle().value

    val transcript = (item as? PodcastEpisode)?.let { ep ->
        remember(ep.provider, ep.itemId) { homeScreenViewModel.transcript(ep) }
            .collectAsStateWithLifecycle().value.dataOrNull
    }

    val lyrics = currentTrack?.lyrics ?: mediaLyricsState.dataOrNull ?: transcript

    val isLoading = (mediaLyricsState is DataState.Loading && lyrics == null) ||
        (item is PodcastEpisode && remember(item.provider, item.itemId) {
            homeScreenViewModel.transcript(item)
        }.collectAsStateWithLifecycle().value is DataState.Loading)

    val effectiveLyrics = lyrics ?: if (isLoading) {
        Lyrics.Plain("Loading lyrics...")
    } else {
        Lyrics.Plain("No lyrics available")
    }

    val queueId = player.queueId
    val livePositionFlow = remember(queueId) {
        queueId?.let { homeScreenViewModel.observePosition(it) }
    }
    content(
        effectiveLyrics,
        livePositionFlow,
        if (item is PodcastEpisode) LyricsSheetKind.TRANSCRIPT else LyricsSheetKind.LYRICS,
    )
}
