package io.music_assistant.client.data.repository

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.music_assistant.client.api.Request
import io.music_assistant.client.api.ServiceClient
import io.music_assistant.client.data.model.client.Lyrics
import io.music_assistant.client.data.model.client.items.Track
import io.music_assistant.client.data.model.client.lyricsOf
import io.music_assistant.client.ui.compose.common.DataState
import io.music_assistant.client.utils.HttpClientFactory
import io.music_assistant.client.utils.LrcParser
import io.music_assistant.client.utils.mapState
import io.music_assistant.client.utils.myJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Resolves per-track lyrics, preferring lyrics embedded in track metadata or
 * returned by the Music Assistant server (metadata/get_track_lyrics), and
 * falling back to the open LRCLIB database (https://lrclib.net) when missing.
 * Supports both library tracks and radio / stream metadata.
 */
class LyricsRepository(
    private val apiClient: ServiceClient,
    private val httpClientFactory: HttpClientFactory,
    private val scope: CoroutineScope,
) {
    private val log = Logger.withTag("LyricsRepository")
    private val httpClient: HttpClient by lazy { httpClientFactory.create() }
    private val states = MutableStateFlow<Map<String, DataState<Lyrics>>>(emptyMap())

    /** The lyrics of [key]; [NoData][DataState.NoData] until [load] is called. */
    fun lyrics(key: String): StateFlow<DataState<Lyrics>> =
        states.mapState { it[key] ?: DataState.NoData() }

    /** The lyrics of [track]; fast-paths to embedded metadata lyrics if present. */
    fun lyrics(track: Track): StateFlow<DataState<Lyrics>> {
        val metadataLyrics = track.lyrics
        if (metadataLyrics != null) {
            return MutableStateFlow(DataState.Data(metadataLyrics))
        }
        val key = makeKey(track.artists.firstOrNull()?.name, track.name)
        return lyrics(key)
    }

    /** Loads lyrics for [track] once if not already available in metadata or cache. */
    fun load(track: Track) {
        val title = track.name
        val artist = track.artists.firstOrNull()?.name.orEmpty()
        val duration = track.duration?.toDouble() ?: 0.0
        load(title, artist, duration, track)
    }

    /** Loads lyrics for given [title] and [artist] (e.g. from radio/stream currentMedia or library). */
    fun load(title: String?, artist: String?, duration: Double = 0.0, track: Track? = null) {
        if (title.isNullOrBlank()) return
        val key = makeKey(artist, title)
        var started = false
        states.update { current ->
            val state = current[key]
            started = state == null || state is DataState.Error
            if (started) current + (key to DataState.Loading()) else current
        }
        if (!started) return

        scope.launch {
            val result = fetchLyrics(title, artist.orEmpty(), duration, track)
            states.update { current ->
                val state = if (result != null) DataState.Data(result) else DataState.NoData()
                current + (key to state)
            }
        }
    }

    private suspend fun fetchLyrics(
        title: String,
        artist: String,
        duration: Double,
        track: Track?,
    ): Lyrics? {
        // Fast-path: track metadata already contains lyrics
        val fromMetadata = track?.lyrics
        if (fromMetadata != null) return fromMetadata

        // 1. Try Music Assistant server API if track is available
        if (track != null) {
            val serverLyrics = fetchFromServer(track)
            if (serverLyrics != null) {
                log.i { "Loaded lyrics from Music Assistant server for $artist - $title" }
                return serverLyrics
            }
        }

        // 2. Fallback to LRCLIB
        val lrclibLyrics = fetchFromLrclib(artist, title, duration)
        if (lrclibLyrics != null) {
            log.i { "Loaded lyrics from LRCLIB for $artist - $title (synced=${lrclibLyrics is Lyrics.Synced})" }
            return lrclibLyrics
        }

        log.d { "No lyrics found for $artist - $title" }
        return null
    }

    private suspend fun fetchFromServer(track: Track): Lyrics? = try {
        val answer = apiClient.sendRequest(Request.Metadata.getTrackLyrics(track)).getOrNull()
        val tuple = answer?.result as? JsonArray
        val plain = tuple?.getOrNull(0)?.jsonPrimitive?.contentOrNull
        val lrc = tuple?.getOrNull(1)?.jsonPrimitive?.contentOrNull
        lyricsOf(plain, lrc)
    } catch (e: Throwable) {
        log.d(e) { "Server getTrackLyrics call failed" }
        null
    }

    private suspend fun fetchFromLrclib(artist: String, title: String, duration: Double): Lyrics? {
        val cleanArtist = cleanQueryText(artist)
        val cleanTitle = cleanQueryText(title)
        val effArtist = cleanArtist.ifEmpty { artist }
        val effTitle = cleanTitle.ifEmpty { title }

        // Step 1: Exact match query
        if (effArtist.isNotEmpty() && effTitle.isNotEmpty()) {
            val exact = queryLrclibGet(effArtist, effTitle, duration)
            if (exact is Lyrics.Synced) return exact
            if (exact != null) {
                // Keep as candidate but see if stripped title has synced
                val strippedTitle = stripParentheticals(effTitle)
                if (strippedTitle.isNotEmpty() && strippedTitle != effTitle) {
                    val stripped = queryLrclibGet(effArtist, strippedTitle, duration)
                    if (stripped is Lyrics.Synced) return stripped
                }
                return exact
            }
        }

        // Step 2: Stripped title without parentheticals
        val strippedTitle = stripParentheticals(effTitle)
        if (strippedTitle.isNotEmpty() && strippedTitle != effTitle) {
            val stripped = queryLrclibGet(effArtist, strippedTitle, duration)
            if (stripped is Lyrics.Synced) return stripped
            if (stripped != null) return stripped
        }

        // Step 2b: If title contains " - ", try splitting artist and title (common on radio metadata)
        if (effTitle.contains(" - ")) {
            val parts = effTitle.split(" - ", limit = 2)
            val splitArtist = cleanQueryText(parts[0])
            val splitTitle = cleanQueryText(parts[1])
            if (splitArtist.isNotEmpty() && splitTitle.isNotEmpty()) {
                val r = queryLrclibGet(splitArtist, splitTitle, duration)
                if (r is Lyrics.Synced) return r
                if (r != null) return r
            }
        }

        // Step 3: Search queries
        val searchQueries = listOfNotNull(
            "$effArtist $effTitle".trim().ifEmpty { null },
            if (strippedTitle != effTitle && strippedTitle.isNotEmpty()) "$effArtist $strippedTitle".trim().ifEmpty { null } else null,
            effTitle.ifEmpty { null }
        ).distinct()

        for (q in searchQueries) {
            val searchResult = queryLrclibSearch(q)
            if (searchResult != null) return searchResult
        }

        return null
    }

    private suspend fun queryLrclibGet(artist: String, title: String, duration: Double): Lyrics? {
        if (title.isBlank()) return null
        return withTimeoutOrNull(LRCLIB_TIMEOUT_MS) {
            try {
                val response = httpClient.get("https://lrclib.net/api/get") {
                    url {
                        parameters.append("artist_name", artist)
                        parameters.append("track_name", title)
                        if (duration > 0.0) {
                            parameters.append("duration", duration.toInt().toString())
                        }
                    }
                    header("User-Agent", LRCLIB_USER_AGENT)
                }
                if (!response.status.isSuccess()) return@withTimeoutOrNull null
                val body = response.bodyAsText()
                val json = myJson.parseToJsonElement(body) as? JsonObject ?: return@withTimeoutOrNull null
                parseLrclibJsonObject(json)
            } catch (e: Throwable) {
                log.d(e) { "queryLrclibGet failed for $artist - $title" }
                null
            }
        }
    }

    private suspend fun queryLrclibSearch(query: String): Lyrics? {
        if (query.isBlank()) return null
        return withTimeoutOrNull(LRCLIB_TIMEOUT_MS) {
            try {
                val response = httpClient.get("https://lrclib.net/api/search") {
                    url {
                        parameters.append("q", query)
                    }
                    header("User-Agent", LRCLIB_USER_AGENT)
                }
                if (!response.status.isSuccess()) return@withTimeoutOrNull null
                val body = response.bodyAsText()
                val array = myJson.parseToJsonElement(body) as? JsonArray ?: return@withTimeoutOrNull null
                var plainFallback: Lyrics? = null
                for (i in 0 until minOf(array.size, 10)) {
                    val item = array[i] as? JsonObject ?: continue
                    val parsed = parseLrclibJsonObject(item) ?: continue
                    if (parsed is Lyrics.Synced) return@withTimeoutOrNull parsed
                    if (plainFallback == null) plainFallback = parsed
                }
                plainFallback
            } catch (e: Throwable) {
                log.d(e) { "queryLrclibSearch failed for $query" }
                null
            }
        }
    }

    private fun parseLrclibJsonObject(obj: JsonObject): Lyrics? {
        val synced = obj["syncedLyrics"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
        if (synced != null) {
            val lines = LrcParser.parse(synced)
            if (lines.isNotEmpty()) return Lyrics.Synced(lines)
        }
        val plain = obj["plainLyrics"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
        if (plain != null) {
            return Lyrics.Plain(plain)
        }
        return null
    }

    private fun stripParentheticals(title: String): String =
        title.replace(Regex("""\s*\([^)]*\)"""), "")
            .replace(Regex("""\s*\[[^]]*\]"""), "")
            .trim()

    private fun cleanQueryText(text: String): String =
        text.replace(Regex("(?i)\\b3rei\\b"), "3")
            .replace(Regex("""\s*[\[\(](feat\.|ft\.|remastered|version|live|deluxe|explicit|clean|mono|stereo|soundtrack|radio edit).*?[\]\)]""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*-\s*(remastered|single|live|radio edit|deluxe|bonus track).*""", RegexOption.IGNORE_CASE), "")
            .trim()

    private fun makeKey(artist: String?, title: String?): String =
        "${artist.orEmpty().trim()} - ${title.orEmpty().trim()}".trim()

    private companion object {
        const val LRCLIB_TIMEOUT_MS = 8000L
        const val LRCLIB_USER_AGENT = "MusicAssistantMobile/1.0 (Echo Show 8; Android)"
    }
}
