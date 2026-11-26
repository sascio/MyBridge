package com.nuvio.app.features.media

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.media.service.*
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun feedLabel(feed: YouTubeFeed): String = stringResource(when (feed) {
    YouTubeFeed.Home -> Res.string.media_home
    YouTubeFeed.Subscriptions -> Res.string.media_subscriptions
    YouTubeFeed.Playlists -> Res.string.media_server_playlists
    YouTubeFeed.Live -> Res.string.media_feed_live
    YouTubeFeed.Music -> Res.string.media_music
    YouTubeFeed.Gaming -> Res.string.media_feed_gaming
    YouTubeFeed.News -> Res.string.media_feed_news
    YouTubeFeed.Sports -> Res.string.media_feed_sports
    YouTubeFeed.Channels -> Res.string.media_feed_channels
    YouTubeFeed.History -> Res.string.media_feed_history
    YouTubeFeed.Shorts -> Res.string.media_feed_shorts
})

internal fun supportedFeeds(signedIn: Boolean): List<YouTubeFeed> = YouTubeFeed.entries.filter {
    it !in setOf(YouTubeFeed.History, YouTubeFeed.Shorts) && (signedIn || it !in setOf(YouTubeFeed.Subscriptions, YouTubeFeed.Playlists))
}

@Composable
internal fun MediaFeedControls(state: MediaBrowseState, selected: YouTubeFeed, signedIn: Boolean, busy: Boolean, onChange: (MediaBrowseState) -> Unit, onSelect: (YouTubeFeed) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    val supported = supportedFeeds(signedIn)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        state.preferences.visibleTabs().filter { it in supported }.ifEmpty { listOf(YouTubeFeed.Home) }.forEach { feed ->
            FilterChip(selected = selected == feed, enabled = !busy, onClick = { onSelect(feed) }, label = { Text(feedLabel(feed)) })
        }
    }
    TextButton(onClick = { editing = !editing }) { Text(stringResource(Res.string.media_feed_settings)) }
    if (editing) {
        val prefs = state.preferences
        Row {
            Checkbox(prefs.rememberTab, { onChange(state.copy(preferences = prefs.copy(rememberTab = it))) })
            Text(stringResource(Res.string.media_remember_feed))
        }
        Row {
            Checkbox(prefs.hideUpcoming, { onChange(state.copy(preferences = prefs.copy(hideUpcoming = it))) })
            Text(stringResource(Res.string.media_hide_upcoming))
        }
        var words by remember { mutableStateOf(prefs.hiddenWords.joinToString(", ")) }
        var channels by remember { mutableStateOf(prefs.hiddenChannels.joinToString(", ")) }
        OutlinedTextField(words, { words = it.take(2000) }, label = { Text(stringResource(Res.string.media_hidden_words)) }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(channels, { channels = it.take(4000) }, label = { Text(stringResource(Res.string.media_hidden_channels)) }, modifier = Modifier.fillMaxWidth())
        TextButton(onClick = { onChange(state.copy(preferences = prefs.copy(
            hiddenWords = words.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
            hiddenChannels = channels.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
        ))) }) { Text(stringResource(Res.string.media_apply_filters)) }
        (prefs.order + YouTubeFeed.entries).distinct().filter { it in supported }.forEach { feed ->
            Row {
                Checkbox(feed !in prefs.hidden, { visible -> onChange(state.copy(preferences = prefs.copy(hidden = if (visible) prefs.hidden - feed else prefs.hidden + feed))) })
                Text(feedLabel(feed), Modifier.weight(1f))
                TextButton(onClick = {
                    val order = (prefs.order + YouTubeFeed.entries).distinct().toMutableList()
                    order.remove(feed); order.add(0, feed)
                    onChange(state.copy(preferences = prefs.copy(order = order)))
                }) { Text(stringResource(Res.string.media_feed_first)) }
            }
        }
    }
}
