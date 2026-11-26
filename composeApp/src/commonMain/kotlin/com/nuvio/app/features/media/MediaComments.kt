package com.nuvio.app.features.media

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.media.service.*
import kotlinx.coroutines.CancellationException
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun MediaComments(service: MediaCommentsService, video: MediaKey) {
    var order by remember(video) { mutableStateOf(CommentOrder.Top) }
    var token by remember(video) { mutableStateOf<String?>(null) }
    var next by remember(video) { mutableStateOf<String?>(null) }
    var comments by remember(video) { mutableStateOf<List<MediaComment>>(emptyList()) }
    var reply by remember(video) { mutableStateOf<MediaComment?>(null) }
    var busy by remember(video) { mutableStateOf(false) }
    var error by remember(video) { mutableStateOf<MediaFailure?>(null) }
    var retry by remember(video) { mutableIntStateOf(0) }
    LaunchedEffect(video, order, token, reply, retry) {
        busy = true; error = null
        if (token == null) { comments = emptyList(); next = null }
        try {
            val page = reply?.let { service.replies(it.id, token) } ?: service.comments(video, order, token)
            comments = (if (token == null) page.items else comments + page.items).distinctBy { it.id }
            next = page.continuation?.takeUnless { it == token }
        } catch (cancelled: CancellationException) { throw cancelled } catch (failure: MediaServiceException) { error = failure.reason } catch (_: Exception) { error = MediaFailure.Unavailable } finally { busy = false }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (reply == null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(enabled = !busy, onClick = { order = CommentOrder.Top; token = null }) { Text(stringResource(Res.string.media_comments_top)) }
            TextButton(enabled = !busy, onClick = { order = CommentOrder.Newest; token = null }) { Text(stringResource(Res.string.media_comments_newest)) }
        } else TextButton(enabled = !busy, onClick = { reply = null; token = null }) { Text(stringResource(Res.string.media_comments_back)) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let {
            MediaFailureText(it)
            TextButton(enabled = !busy, onClick = { retry++ }) { Text(stringResource(Res.string.media_retry_action)) }
        }
        if (!busy && error == null && comments.isEmpty()) Text(stringResource(Res.string.media_no_results))
        comments.forEach { comment ->
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(comment.author, style = MaterialTheme.typography.titleSmall)
                Text(comment.text, style = MaterialTheme.typography.bodyMedium)
                if (reply == null && comment.replyCount > 0) TextButton(enabled = !busy, onClick = { reply = comment; token = null }) { Text(stringResource(Res.string.media_comments_replies, comment.replyCount)) }
            }
        }
        if (error == null && next != null && comments.size < 300) TextButton(enabled = !busy, onClick = { token = next }) { Text(stringResource(Res.string.media_load_more)) }
    }
}
