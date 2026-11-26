package com.nuvio.app.features.media

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.media.service.*
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/** Device flow uses the system browser on a registered Google verification page.
 * This is a real grant; entering a client ID is never treated as an authenticated account. */
@Composable
internal fun GoogleAccountConnect(
    client: HttpClient, store: MediaSessionStore, profileId: Int, serviceId: MediaServiceId,
    onBusy: (Boolean) -> Unit,
    onConnected: (MediaAccountSession) -> Unit,
) {
    var clientId by remember { mutableStateOf("") }
    var clientSecret by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<GoogleOAuthRegistration?>(null) }
    var challenge by remember { mutableStateOf<GoogleDeviceChallenge?>(null) }
    var failure by remember { mutableStateOf<MediaFailure?>(null) }
    val uriHandler = LocalUriHandler.current
    val auth = remember(client) { GoogleDeviceAuthService(client) }
    val latestConnected by rememberUpdatedState(onConnected)
    val latestBusy by rememberUpdatedState(onBusy)
    LaunchedEffect(pending) {
        val registration = pending ?: return@LaunchedEffect
        failure = null
        latestBusy(true)
        try {
            val code = auth.begin(registration)
            challenge = code
            val token = auth.awaitToken(registration, code)
            val account = YouTubeDataService(client, accessToken = { token.accessToken }).account()
            val refresh = token.refreshToken?.let { Json.encodeToString(GoogleRefreshCredential(registration, it)) }
            val session = MediaAccountSession("google:${account.accountId}", account.displayName, token.accessToken, refresh, token.expiresAtEpochMs, account.channelId)
            store.save(profileId, serviceId, session)
            clientSecret = ""
            latestConnected(session)
        } catch (cancelled: CancellationException) { throw cancelled } catch (error: MediaServiceException) { failure = error.reason } catch (_: Exception) { failure = MediaFailure.Unavailable } finally { latestBusy(false); if (pending === registration) { challenge = null; pending = null } }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(Res.string.media_google_signin_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(Res.string.media_google_registration_notice), style = MaterialTheme.typography.bodySmall)
        if (pending == null) {
            OutlinedTextField(clientId, { clientId = it.take(512) }, singleLine = true, label = { Text(stringResource(Res.string.media_google_client_id)) }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(clientSecret, { clientSecret = it.take(512) }, singleLine = true, label = { Text(stringResource(Res.string.media_google_client_secret)) }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Button(enabled = clientId.isNotBlank() && clientSecret.isNotBlank(), onClick = {
                try { pending = GoogleOAuthRegistration(clientId.trim(), clientSecret.trim()) } catch (_: Exception) { failure = MediaFailure.InvalidInput }
            }) { Text(stringResource(Res.string.media_google_start_signin)) }
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            challenge?.let { code ->
                SelectionContainer { Text(code.userCode, style = MaterialTheme.typography.headlineMedium) }
                Text(stringResource(Res.string.media_google_enter_code))
                Button(onClick = {
                    try { uriHandler.openUri(code.verificationUrl) } catch (_: Exception) { failure = MediaFailure.Unavailable }
                }) { Text(stringResource(Res.string.media_google_open_verification)) }
            }
            TextButton(onClick = { pending = null; challenge = null }) { Text(stringResource(Res.string.media_cancel)) }
        }
        failure?.let { MediaFailureText(it) }
    }
}
