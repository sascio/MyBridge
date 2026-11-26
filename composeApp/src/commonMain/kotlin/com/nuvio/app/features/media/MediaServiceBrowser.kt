package com.nuvio.app.features.media

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.network.createApiHttpClient
import com.nuvio.app.features.media.service.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/** Live catalogue/library connection UI. No playback/download/account actions are
 * shown until there is an adapter that can actually perform them. */
@Composable
internal fun MediaServiceBrowser(music: Boolean, destination: String) {
    val auth by com.nuvio.app.core.auth.AuthRepository.state.collectAsState()
    val profiles by com.nuvio.app.features.profiles.ProfileRepository.state.collectAsState()
    val ownerId = (auth as? com.nuvio.app.core.auth.AuthState.Authenticated)?.userId
    val activeProfile = profiles.activeProfile
    if (ownerId.isNullOrBlank() || activeProfile == null || activeProfile.userId != ownerId) {
        Text(stringResource(Res.string.media_profile_unavailable))
        return
    }
    val profileId = activeProfile.profileIndex
    var musicSource by remember(ownerId, profileId) { mutableStateOf(MediaServiceId.Navidrome) }
    val serviceId = if (music) musicSource else MediaServiceId.YouTube
    val navidrome = serviceId == MediaServiceId.Navidrome
    val webDav = serviceId == MediaServiceId.WebDav
    if (music) Column {
        TextButton(onClick = { musicSource = MediaServiceId.Navidrome }) { Text(stringResource(Res.string.media_navidrome_title)) }
        TextButton(onClick = { musicSource = MediaServiceId.YouTubeMusic }) { Text(stringResource(Res.string.media_google_music_title)) }
        TextButton(onClick = { musicSource = MediaServiceId.WebDav }) { Text(stringResource(Res.string.media_webdav_title)) }
    }
    val store = remember(ownerId) { MediaSessionStore(ownerId) }
    val restored = remember(ownerId, profileId, serviceId) {
        try { store.active(profileId, serviceId) to null } catch (_: Exception) { null to MediaFailure.Authentication }
    }
    var session by remember(ownerId, profileId, serviceId) { mutableStateOf(restored.first) }
    var failure by remember(ownerId, profileId, serviceId) { mutableStateOf(restored.second) }
    var server by remember(ownerId, profileId, serviceId) { mutableStateOf("") }
    var username by remember(ownerId, profileId, serviceId) { mutableStateOf("") }
    var token by remember(ownerId, profileId, serviceId) { mutableStateOf("") }
    var salt by remember(ownerId, profileId, serviceId) { mutableStateOf("") }
    var apiKey by remember(ownerId, profileId, serviceId) { mutableStateOf("") }
    var password by remember(ownerId, profileId, serviceId) { mutableStateOf("") }
    var candidate by remember(ownerId, profileId, serviceId) { mutableStateOf<MediaAccountSession?>(null) }
    var connecting by remember(ownerId, profileId, serviceId) { mutableStateOf(false) }
    var oauthConnecting by remember(ownerId, profileId, serviceId) { mutableStateOf(false) }
    val client = remember { createApiHttpClient() }
    DisposableEffect(client) { onDispose { client.close() } }
    val json = remember { Json { ignoreUnknownKeys = true } }

    LaunchedEffect(ownerId, profileId, serviceId, candidate) {
        val next = candidate ?: return@LaunchedEffect
        connecting = true
        failure = null
        try {
            if (webDav) WebDavService(client, json.decodeFromString<WebDavConnection>(next.credential)).home()
            else if (navidrome) NavidromeService(client, json.decodeFromString<NavidromeConnection>(next.credential)).ping()
            else YouTubeDataService(client, next.credential).home()
            store.save(profileId, serviceId, next)
            session = next
            token = ""; salt = ""; apiKey = ""; password = ""
        } catch (cancelled: CancellationException) { throw cancelled } catch (error: MediaServiceException) { failure = error.reason } catch (_: Exception) { failure = MediaFailure.InvalidInput } finally { connecting = false; candidate = null }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(if (webDav) Res.string.media_webdav_title else if (navidrome) Res.string.media_navidrome_title else if (music) Res.string.media_google_music_title else Res.string.media_public_youtube_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(if (webDav) Res.string.media_webdav_notice else if (navidrome) Res.string.media_navidrome_notice else if (music) Res.string.media_google_music_notice else Res.string.media_youtube_api_notice), style = MaterialTheme.typography.bodyMedium)
        failure?.let { MediaFailureText(it) }
        if (session == null) {
            if (!navidrome && !webDav) key(ownerId, profileId, serviceId) {
                GoogleAccountConnect(client, store, profileId, serviceId, onBusy = { oauthConnecting = it }) { connected -> session = connected; failure = null }
            }
            if (navidrome || webDav) {
                OutlinedTextField(server, { server = it }, label = { Text(stringResource(Res.string.media_server_url)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                OutlinedTextField(username, { username = it }, label = { Text(stringResource(Res.string.media_server_username)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (webDav) OutlinedTextField(password, { password = it }, label = { Text(stringResource(Res.string.media_webdav_password)) }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                else {
                OutlinedTextField(token, { token = it }, label = { Text(stringResource(Res.string.media_subsonic_token)) }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(salt, { salt = it }, label = { Text(stringResource(Res.string.media_subsonic_salt)) }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                }
            } else {
                OutlinedTextField(apiKey, { apiKey = it }, label = { Text(stringResource(Res.string.media_api_key)) }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            }
            Text(stringResource(if (webDav) Res.string.media_webdav_storage_notice else Res.string.media_secure_connection_notice), style = MaterialTheme.typography.bodySmall)
            Button(enabled = !connecting && !oauthConnecting && if (webDav) server.isNotBlank() && username.isNotBlank() && password.isNotBlank() else if (navidrome) server.isNotBlank() && username.isNotBlank() && token.isNotBlank() && salt.isNotBlank() else apiKey.isNotBlank(), onClick = {
                try {
                    val credential = if (webDav) json.encodeToString(WebDavConnection(server.trim(), username.trim(), password)) else if (navidrome) json.encodeToString(NavidromeConnection(server.trim(), username.trim(), token.trim(), salt.trim())) else apiKey.trim()
                    candidate = MediaAccountSession("connection", if (navidrome || webDav) username.trim() else "YouTube Data API", credential)
                } catch (_: Exception) { failure = MediaFailure.InvalidInput }
            }) { Text(stringResource(Res.string.media_connect)) }
            if (connecting) LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            TextButton(onClick = {
                try {
                    store.remove(profileId, serviceId, session!!.accountId)
                    session = store.active(profileId, serviceId)
                    failure = null
                } catch (_: Exception) { failure = MediaFailure.Unavailable }
            }) { Text(stringResource(Res.string.media_disconnect)) }
            if (!navidrome && !webDav) {
                Text(session!!.displayName, style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { session = null }) { Text(stringResource(Res.string.media_add_account)) }
                val accountsResult = runCatching { store.accounts(profileId, serviceId) }
                if (accountsResult.isFailure) MediaFailureText(MediaFailure.Authentication)
                accountsResult.getOrDefault(emptyList()).filter { it.accountId != session!!.accountId }.forEach { account ->
                    TextButton(onClick = {
                        try { store.select(profileId, serviceId, account.accountId); session = store.active(profileId, serviceId); failure = null } catch (_: Exception) { failure = MediaFailure.Authentication }
                    }) { Text(account.displayName) }
                }
            }
            val active = session!!
            val catalogue: CatalogueService? = remember(active, music) {
                try {
                    if (webDav) WebDavService(client, json.decodeFromString<WebDavConnection>(active.credential))
                    else if (navidrome) NavidromeService(client, json.decodeFromString<NavidromeConnection>(active.credential))
                    else if (active.accountId.startsWith("google:")) {
                        val access = GoogleSessionAccess(store, profileId, serviceId, active.accountId, GoogleDeviceAuthService(client))
                        YouTubeDataService(client, accessToken = { access.token() }, musicOnly = music)
                    } else YouTubeDataService(client, active.credential, musicOnly = music)
                } catch (_: Exception) { null }
            }
            if (catalogue == null) MediaFailureText(MediaFailure.InvalidInput)
            else key(destination, active) { ConnectedCatalogue(catalogue, destination, ownerId, profileId, store, serviceId, active.accountId) }
        }
    }
}

@Composable
private fun ConnectedCatalogue(catalogue: CatalogueService, destination: String, ownerId: String, profileId: Int, store: MediaSessionStore, serviceId: MediaServiceId, accountId: String) {
    val playMedia = LocalPlayMedia.current
    val youtube = catalogue as? YouTubeDataService
    val libraryAvailable = catalogue is NavidromeService || youtube?.signedIn == true
    val browsingResult = remember { runCatching { store.browsing(profileId, serviceId, accountId) } }
    var browsing by remember { mutableStateOf(browsingResult.getOrDefault(MediaBrowseState())) }
    var feed by remember { mutableStateOf(browsing.preferences.initialTab().takeIf { it in supportedFeeds(youtube?.signedIn == true) } ?: YouTubeFeed.Home) }
    var browseStorageFailure by remember { mutableStateOf(browsingResult.isFailure) }
    LaunchedEffect(browsing) {
        try { store.saveBrowsing(profileId, serviceId, accountId, browsing); browseStorageFailure = false }
        catch (_: Exception) { browseStorageFailure = true }
    }
    if (browseStorageFailure) MediaFailureText(MediaFailure.Unavailable)

    var query by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf("") }
    var route by remember { mutableStateOf<MediaEntry?>(null) }
    var subscriptionId by remember(route) { mutableStateOf<String?>(null) }
    var subscriptionLoaded by remember(route) { mutableStateOf(false) }
    var subscriptionError by remember(route) { mutableStateOf<MediaFailure?>(null) }
    LaunchedEffect(route, youtube) {
        val channel = route?.takeIf { it.kind == MediaKind.Channel } ?: return@LaunchedEffect
        if (youtube?.signedIn != true) return@LaunchedEffect
        try { subscriptionId = youtube.subscriptionId(channel.key); subscriptionLoaded = true } catch (cancelled: CancellationException) { throw cancelled } catch (error: MediaServiceException) { subscriptionError = error.reason } catch (_: Exception) { subscriptionError = MediaFailure.Unavailable }
    }

    var pageToken by remember { mutableStateOf<String?>(null) }
    var nextToken by remember { mutableStateOf<String?>(null) }
    var entries by remember { mutableStateOf<List<MediaEntry>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<MediaFailure?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var loaded by remember { mutableStateOf(false) }
    var libraryMode by remember { mutableStateOf("playlists") }
    var radioSeed by remember { mutableStateOf<MediaKey?>(null) }
    var playlistTitle by remember { mutableStateOf("") }
    var deleteRequested by remember { mutableStateOf<MediaEntry?>(null) }
    var saving by remember { mutableStateOf<MediaEntry?>(null) }
    var choices by remember { mutableStateOf<List<MediaEntry>>(emptyList()) }
    var shownLyrics by remember { mutableStateOf<MediaLyrics?>(null) }
    var lyricsMissing by remember { mutableStateOf(false) }
    var commentsFor by remember { mutableStateOf<MediaKey?>(null) }
    var mutationBusy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val library = catalogue as? MediaLibraryService
    val restoredQueue = remember { runCatching { store.queue(profileId, serviceId, accountId) } }
    var queue by remember { mutableStateOf(restoredQueue.getOrDefault(MediaQueue())) }
    LaunchedEffect(queue) {
        try { store.saveQueue(profileId, serviceId, accountId, queue) }
        catch (_: Exception) { failure = MediaFailure.Unavailable }
    }
    if (restoredQueue.isFailure) MediaFailureText(MediaFailure.Unavailable)
    fun mutate(operation: suspend () -> Unit) {
        if (mutationBusy || busy) return
        mutationBusy = true
        scope.launch {
            failure = null
            try { operation() } catch (cancelled: CancellationException) { throw cancelled } catch (error: MediaServiceException) { failure = error.reason } catch (_: Exception) { failure = MediaFailure.Unavailable } finally { mutationBusy = false }
        }
    }
    fun playQueue(position: Int) {
        val resolver = catalogue as? MediaResolver ?: return
        val player = playMedia ?: return
        if (position !in queue.entries.indices) return
        val selected = queue.select(position)
        val entry = selected.current ?: return
        mutate {
            val resolved = resolver.resolve(entry.key)
            player(MediaPlaybackRequest(ownerId, profileId, entry, resolved,
                previous = if (position > 0) { { playQueue(queue.index - 1) } } else null,
                next = if (selected.next(manual = true) != null) { { queue.next(manual = true)?.let { playQueue(it.index) } } } else null,
                onEnded = { queue.next()?.let { playQueue(it.index) } },
            ))
            queue = selected
        }
    }
    if (catalogue is MediaResolver && queue.entries.isNotEmpty()) {
        Text(stringResource(Res.string.media_queue_title), style = MaterialTheme.typography.titleMedium)
        TextButton(enabled = !mutationBusy, onClick = { queue = queue.copy(entries = queue.entries.shuffled(), index = 0) }) { Text(stringResource(Res.string.media_queue_shuffle)) }
        TextButton(enabled = !mutationBusy, onClick = { queue = queue.copy(repeat = QueueRepeat.entries[(queue.repeat.ordinal + 1) % QueueRepeat.entries.size]) }) {
            Text(stringResource(when (queue.repeat) {
                QueueRepeat.Off -> Res.string.media_repeat_off
                QueueRepeat.All -> Res.string.media_repeat_all
                QueueRepeat.One -> Res.string.media_repeat_one
            }))
        }
        queue.entries.forEachIndexed { index, item ->
            Column {
                TextButton(enabled = !mutationBusy, onClick = { playQueue(index) }) { Text(item.title) }
                Row {
                    if (index > 0) TextButton(enabled = !mutationBusy, onClick = { queue = queue.move(index, index - 1) }) { Text(stringResource(Res.string.media_queue_up)) }
                    TextButton(enabled = !mutationBusy, onClick = { queue = queue.remove(index) }) { Text(stringResource(Res.string.media_queue_remove)) }
                }
            }
        }
    }
    if (saving != null) AlertDialog(
        onDismissRequest = { if (!mutationBusy) saving = null },
        title = { Text(stringResource(Res.string.media_save_playlist)) },
        text = {
            Column {
                if (choices.isEmpty()) Text(stringResource(Res.string.media_no_results))
                choices.take(100).forEach { playlist ->
                    TextButton(enabled = !mutationBusy, onClick = {
                        val item = saving!!
                        mutate { library!!.addToPlaylist(playlist.key, listOf(item.key)); saving = null }
                    }) { Text(playlist.title) }
                }
                failure?.let { MediaFailureText(it) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(enabled = !mutationBusy, onClick = { saving = null }) { Text(stringResource(Res.string.media_cancel)) } },
    )
    deleteRequested?.let { playlist ->
        AlertDialog(onDismissRequest = { if (!mutationBusy) deleteRequested = null },
            title = { Text(stringResource(Res.string.media_delete_playlist)) }, text = { Column { Text(playlist.title); failure?.let { MediaFailureText(it) } } },
            confirmButton = { TextButton(enabled = !mutationBusy, onClick = { mutate { library!!.deletePlaylist(playlist.key); deleteRequested = null; route = null; retry++ } }) { Text(stringResource(Res.string.media_delete_playlist)) } },
            dismissButton = { TextButton(enabled = !mutationBusy, onClick = { deleteRequested = null }) { Text(stringResource(Res.string.media_cancel)) } })
    }
    shownLyrics?.let { lyrics ->
        Text(stringResource(if (lyrics.synced) Res.string.media_timed_lyrics else Res.string.media_plain_lyrics), style = MaterialTheme.typography.titleMedium)
        Text(lyrics.lines.joinToString("\n") { it.text })
        TextButton(onClick = { shownLyrics = null }) { Text(stringResource(Res.string.media_close_lyrics)) }
    }
    if (lyricsMissing) Text(stringResource(Res.string.media_lyrics_unavailable))
    val unsupportedLibrary = !libraryAvailable && catalogue !is WebDavService && destination == "Library"
    LaunchedEffect(route, submitted, pageToken, retry, libraryMode, radioSeed, feed) {
        if (unsupportedLibrary) return@LaunchedEffect
        busy = true; failure = null
        if (pageToken == null) { entries = emptyList(); nextToken = null; loaded = false }
        try {
            val result = when {
                route != null -> catalogue.browse(route!!.key, pageToken)
                radioSeed != null && catalogue is NavidromeService -> MediaPage(catalogue.radio(radioSeed!!))
                libraryMode == "random" && catalogue is NavidromeService -> MediaPage(catalogue.randomTracks())
                submitted.isNotBlank() -> catalogue.search(submitted, pageToken)
                libraryAvailable && destination == "Library" && libraryMode == "liked" -> MediaPage(library!!.liked())
                libraryAvailable && destination == "Library" && libraryMode == "subscriptions" -> youtube!!.subscriptions(pageToken)
                libraryAvailable && destination == "Library" -> MediaPage(library!!.playlists())
                youtube != null -> youtube.feed(feed, pageToken)
                else -> catalogue.home(pageToken)
            }
            entries = (if (pageToken == null) result.items else entries + result.items).distinctBy { it.key }
            nextToken = result.continuation?.takeUnless { it == pageToken }
            loaded = true
        } catch (cancelled: CancellationException) { throw cancelled } catch (error: MediaServiceException) { failure = error.reason } catch (_: Exception) { failure = MediaFailure.Unavailable } finally { busy = false }
    }
    if (unsupportedLibrary) {
        Text(stringResource(Res.string.media_youtube_library_unavailable))
        return
    }
    if (libraryAvailable && destination == "Library" && route == null) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(enabled = !busy && !mutationBusy, onClick = { radioSeed = null; libraryMode = "playlists"; pageToken = null; submitted = "" }) { Text(stringResource(Res.string.media_server_playlists)) }
            TextButton(enabled = !busy && !mutationBusy, onClick = { radioSeed = null; libraryMode = "liked"; pageToken = null; submitted = "" }) { Text(stringResource(Res.string.media_server_liked)) }
            if (youtube?.signedIn == true) TextButton(enabled = !busy && !mutationBusy, onClick = { radioSeed = null; libraryMode = "subscriptions"; pageToken = null; submitted = "" }) { Text(stringResource(Res.string.media_subscriptions)) }
        }
        OutlinedTextField(playlistTitle, { playlistTitle = it.take(200) }, label = { Text(stringResource(Res.string.media_playlist_title)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(enabled = !busy && !mutationBusy && playlistTitle.isNotBlank(), onClick = {
            val title = playlistTitle
            mutate { library!!.createPlaylist(title); playlistTitle = ""; retry++ }
        }) { Text(stringResource(Res.string.media_create_playlist)) }
    }
    if (youtube != null && destination != "Library") MediaFeedControls(browsing, feed, youtube.signedIn, busy || mutationBusy,
        onChange = { browsing = it }, onSelect = { selected ->
            feed = selected; submitted = ""; query = ""; route = null; pageToken = null
            browsing = browsing.copy(preferences = browsing.preferences.copy(lastTab = selected))
        })
    if (query.isNotBlank()) browsing.searches.filter { it.contains(query, ignoreCase = true) && it != query }.take(5).forEach { previous ->
        TextButton(enabled = !busy && !mutationBusy, onClick = { query = previous }) { Text(previous) }
    }
    if (browsing.searches.isNotEmpty()) TextButton(onClick = { browsing = browsing.copy(searches = emptyList()) }) { Text(stringResource(Res.string.media_clear_searches)) }
    OutlinedTextField(query, { query = it.take(500) }, label = { Text(stringResource(Res.string.media_catalogue_search)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = query.isNotBlank() && !busy && !mutationBusy, onClick = { radioSeed = null; route = null; pageToken = null; submitted = query.trim(); browsing = browsing.searched(query); libraryMode = "playlists"; retry++ }) { Text(stringResource(Res.string.media_search_action)) }
        TextButton(enabled = !busy && !mutationBusy, onClick = { radioSeed = null; route = null; pageToken = null; submitted = ""; query = ""; libraryMode = "playlists"; retry++ }) { Text(stringResource(Res.string.media_catalogue_home)) }
    }
    if (catalogue is NavidromeService) TextButton(enabled = !busy && !mutationBusy, onClick = { radioSeed = null; route = null; submitted = ""; pageToken = null; libraryMode = "random"; retry++ }) { Text(stringResource(Res.string.media_random_tracks)) }
    route?.let { current ->
        Text(current.title, style = MaterialTheme.typography.titleMedium)
        subscriptionError?.let { MediaFailureText(it) }
        if (current.kind == MediaKind.Channel && youtube?.signedIn == true && subscriptionLoaded) {
            TextButton(enabled = !busy && !mutationBusy, onClick = {
                mutate {
                    val existing = subscriptionId
                    if (existing == null) subscriptionId = youtube.subscribe(current.key)
                    else { youtube.unsubscribe(existing); subscriptionId = null }
                }
            }) { Text(stringResource(if (subscriptionId == null) Res.string.media_subscribe else Res.string.media_unsubscribe)) }
        }

        if (libraryAvailable && current.kind == MediaKind.Playlist) TextButton(enabled = !busy && !mutationBusy, onClick = { deleteRequested = current }) { Text(stringResource(Res.string.media_delete_playlist)) }
        TextButton(enabled = !busy && !mutationBusy, onClick = { route = null; pageToken = null }) { Text(stringResource(Res.string.media_browse_back)) }
    }
    if (busy || mutationBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
    failure?.let {
        MediaFailureText(it)
        TextButton(enabled = !busy && !mutationBusy, onClick = { retry++ }) { Text(stringResource(Res.string.media_retry_action)) }
    }
    if (loaded && entries.none { youtube == null || browsing.preferences.allows(it) }) Text(stringResource(Res.string.media_no_results))
    entries.filter { youtube == null || browsing.preferences.allows(it) }.forEach { entry ->
        Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                entry.artwork?.let { artwork ->
                    coil3.compose.AsyncImage(
                        model = artwork, contentDescription = null,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).aspectRatio(16f / 9f),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    )
                }
                Text(entry.title, style = MaterialTheme.typography.titleMedium)
                if (entry.subtitle.isNotBlank()) Text(entry.subtitle, style = MaterialTheme.typography.bodyMedium)
                if (entry.kind in setOf(MediaKind.Album, MediaKind.Playlist, MediaKind.Channel)) {
                    TextButton(enabled = !busy && !mutationBusy, onClick = { route = entry; pageToken = null }) { Text(stringResource(Res.string.media_browse_action)) }
                } else {
                    if (catalogue is MediaResolver && playMedia != null) {
                        if (com.nuvio.app.features.player.MiniPlayerController.isActive) Text(stringResource(Res.string.media_close_miniplayer_first))
                        Button(enabled = !busy && !mutationBusy && !com.nuvio.app.features.player.MiniPlayerController.isActive, onClick = {
                            val tracks = entries.filter { it.kind in setOf(MediaKind.Track, MediaKind.Video) }
                            val selectedIndex = tracks.indexOfFirst { it.key == entry.key }
                            val window = tracks.drop((selectedIndex - 299).coerceAtLeast(0)).take(300)
                            queue = MediaQueue(window, window.indexOfFirst { it.key == entry.key }, queue.repeat)
                            playQueue(queue.index)
                        }) { Text(stringResource(Res.string.media_play_action)) }
                        TextButton(enabled = !mutationBusy && queue.entries.size < 300, onClick = { queue = queue.append(entry) }) { Text(stringResource(Res.string.media_queue_add)) }
                    } else Text(stringResource(Res.string.media_playback_not_connected), style = MaterialTheme.typography.bodySmall)
                    if (libraryAvailable) TextButton(enabled = !busy && !mutationBusy, onClick = { mutate { choices = library!!.playlists(); saving = entry } }) { Text(stringResource(Res.string.media_save_playlist)) }
                    if (catalogue is NavidromeService) {
                        TextButton(enabled = !busy && !mutationBusy, onClick = {
                            mutate { shownLyrics = catalogue.lyrics(entry); lyricsMissing = shownLyrics == null }
                        }) { Text(stringResource(Res.string.media_show_lyrics)) }
                        TextButton(enabled = !busy && !mutationBusy, onClick = {
                            mutate { radioSeed = entry.key; route = null; submitted = ""; pageToken = null; libraryMode = "radio" }
                        }) { Text(stringResource(Res.string.media_start_radio)) }
                    }
                    if (catalogue is MediaCommentsService && entry.kind == MediaKind.Video) {
                        TextButton(onClick = { commentsFor = if (commentsFor == entry.key) null else entry.key }) { Text(stringResource(Res.string.media_show_comments)) }
                        if (commentsFor == entry.key) MediaComments(catalogue, entry.key)
                    }

                    if (libraryAvailable && entry.kind in setOf(MediaKind.Track, MediaKind.Video) && library != null) {
                        TextButton(enabled = !busy && !mutationBusy, onClick = {
                            mutate {
                                library.setLiked(entry.key, !entry.liked)
                                entries = entries.map { if (it.key == entry.key) it.copy(liked = !entry.liked) else it }
                            }
                        }) { Text(stringResource(if (entry.liked) Res.string.media_unlike_track else Res.string.media_like_track)) }
                    }
                }
            }
        }
    }
    val nearEnd = LocalMediaNearEnd.current
    LaunchedEffect(nearEnd, busy, mutationBusy, nextToken, failure) {
        if (nearEnd && loaded && !busy && !mutationBusy && failure == null && entries.size < 300 && nextToken != null && nextToken != pageToken)
            pageToken = nextToken
    }
    if (failure == null && nextToken != null && entries.size < 300) {
        TextButton(enabled = !busy && !mutationBusy, onClick = { pageToken = nextToken }) { Text(stringResource(Res.string.media_load_more)) }
    } else if (entries.size >= 300) Text(stringResource(Res.string.media_page_limit))
}

@Composable
internal fun MediaFailureText(failure: MediaFailure) {
    Text(stringResource(when (failure) {
        MediaFailure.Authentication -> Res.string.media_error_auth
        MediaFailure.Permission -> Res.string.media_error_permission
        MediaFailure.RateLimited -> Res.string.media_error_rate
        MediaFailure.Network -> Res.string.media_error_network
        MediaFailure.InvalidInput -> Res.string.media_error_input
        MediaFailure.InvalidResponse -> Res.string.media_error_response
        MediaFailure.Unavailable -> Res.string.media_error_unavailable
    }), color = MaterialTheme.colorScheme.error)
}
