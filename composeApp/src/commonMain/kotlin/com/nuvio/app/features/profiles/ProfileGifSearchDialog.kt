package com.nuvio.app.features.profiles

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.NuvioInputField
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.action_retry
import nuvio.composeapp.generated.resources.profile_gif_search_description
import nuvio.composeapp.generated.resources.profile_gif_search_empty
import nuvio.composeapp.generated.resources.profile_gif_search_error
import nuvio.composeapp.generated.resources.profile_gif_search_loading
import nuvio.composeapp.generated.resources.profile_gif_search_no_results
import nuvio.composeapp.generated.resources.profile_gif_search_not_configured
import nuvio.composeapp.generated.resources.profile_gif_search_placeholder
import nuvio.composeapp.generated.resources.profile_gif_search_powered_by
import nuvio.composeapp.generated.resources.profile_gif_search_submit
import nuvio.composeapp.generated.resources.profile_gif_search_title
import org.jetbrains.compose.resources.stringResource

/**
 * GIF search picker for the profile avatar. Picking a result only fills the Custom
 * Avatar URL field; the profile is saved by the editor's normal Save action.
 */
@Composable
internal fun ProfileGifSearchDialog(
    onSelect: (ProfileGifSearchItem) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var lastSearchedQuery by rememberSaveable { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<ProfileGifSearchItem>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    val searchFailedMessage = stringResource(Res.string.profile_gif_search_error)
    val notConfiguredMessage = stringResource(Res.string.profile_gif_search_not_configured)

    fun runSearch(text: String) {
        val cleanQuery = text.trim()
        if (cleanQuery.isEmpty()) return
        // Cancel an obsolete search before starting a new one so responses cannot race.
        searchJob?.cancel()
        isSearching = true
        errorMessage = null
        lastSearchedQuery = cleanQuery
        searchJob = scope.launch {
            val outcome = ProfileGifSearchService.search(cleanQuery)
            isSearching = false
            outcome.fold(
                onSuccess = { items ->
                    results = items
                    errorMessage = null
                },
                onFailure = { throwable ->
                    // A failed request must not show stale results as if they were current.
                    results = emptyList()
                    errorMessage = if (throwable is ProfileGifSearchNotConfiguredException) {
                        notConfiguredMessage
                    } else {
                        searchFailedMessage
                    }
                },
            )
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            shadowElevation = 18.dp,
        ) {
            Column(
                modifier = Modifier
                    .heightIn(max = 680.dp)
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = stringResource(Res.string.profile_gif_search_title),
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = stringResource(Res.string.profile_gif_search_description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(Res.string.action_cancel),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    NuvioInputField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = stringResource(Res.string.profile_gif_search_placeholder),
                    )
                    Button(
                        onClick = { runSearch(query) },
                        enabled = query.isNotBlank() && !isSearching,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        if (isSearching) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Search,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isSearching) {
                                stringResource(Res.string.profile_gif_search_loading)
                            } else {
                                stringResource(Res.string.profile_gif_search_submit)
                            },
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                errorMessage?.let { message ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        // Retry only the same query, and only when the provider failed.
                        if (errorMessage != notConfiguredMessage && lastSearchedQuery != null) {
                            TextButton(onClick = { runSearch(lastSearchedQuery.orEmpty()) }) {
                                Text(text = stringResource(Res.string.action_retry))
                            }
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when {
                        isSearching -> ProfileGifSearchEmptyState(
                            text = stringResource(Res.string.profile_gif_search_loading),
                        )
                        errorMessage != null -> Unit
                        results.isEmpty() -> ProfileGifSearchEmptyState(
                            text = if (lastSearchedQuery == null) {
                                stringResource(Res.string.profile_gif_search_empty)
                            } else {
                                stringResource(Res.string.profile_gif_search_no_results)
                            },
                        )
                        else -> results.chunked(2).forEach { rowItems ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                rowItems.forEach { item ->
                                    ProfileGifResultCard(
                                        item = item,
                                        modifier = Modifier.weight(1f),
                                        onClick = { onSelect(item) },
                                    )
                                }
                                // Keep a lone final card at half width.
                                if (rowItems.size == 1) Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(Res.string.profile_gif_search_powered_by),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onDismiss) {
                        Text(text = stringResource(Res.string.action_cancel))
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileGifResultCard(
    item: ProfileGifSearchItem,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier
            .height(150.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            NuvioAsyncImage(
                imageUrl = item.previewUrl,
                contentDescription = item.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                animateIfPossible = false,
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.46f))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ProfileGifSearchEmptyState(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 18.dp),
        )
    }
}
