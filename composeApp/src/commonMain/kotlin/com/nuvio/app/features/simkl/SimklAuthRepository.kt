package com.nuvio.app.features.simkl

import co.touchlab.kermit.Logger
import com.nuvio.app.core.build.RuntimeCredentials
import com.nuvio.app.features.tracking.TrackingAuthProvider
import com.nuvio.app.features.tracking.TrackingCapability
import com.nuvio.app.features.tracking.TrackingProviderDescriptor
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingProviderRegistry
import com.nuvio.app.features.tracking.TrackingRefreshIntent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object SimklAuthRepository : TrackingAuthProvider {
    private val log = Logger.withTag("SimklAuth")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val authorizationMutex = Mutex()

    private val _uiState = MutableStateFlow(SimklAuthUiState())
    val uiState: StateFlow<SimklAuthUiState> = _uiState.asStateFlow()

    private val _isAuthenticated = MutableStateFlow(false)
    override val isAuthenticated: StateFlow<Boolean> = _isAuthenticated.asStateFlow()

    override val descriptor = TrackingProviderDescriptor(
        id = TrackingProviderId.SIMKL,
        displayName = "Simkl",
        capabilities = setOf(
            TrackingCapability.AUTHENTICATION,
            TrackingCapability.LIBRARY_READ,
            TrackingCapability.LIBRARY_WRITE,
            TrackingCapability.WATCHED_READ,
            TrackingCapability.WATCHED_WRITE,
            TrackingCapability.PROGRESS_READ,
            TrackingCapability.PROGRESS_WRITE,
            TrackingCapability.SCROBBLE,
            TrackingCapability.RATINGS,
        ),
    )

    private var hasLoaded = false
    private var storedState = SimklStoredAuthState()
    private var accessToken: String? = null
    private var authenticationMethod = SimklAuthenticationMethod.BROWSER_REDIRECT
    private var pinPollingJob: Job? = null
    private var refreshJob: Job? = null
    private val refreshMutex = Mutex()

    init {
        TrackingProviderRegistry.register(this)
    }

    override fun ensureLoaded() {
        if (hasLoaded) return
        loadFromDisk()
    }

    override fun onProfileChanged() {
        loadFromDisk()
    }

    override fun clearLocalState() {
        hasLoaded = false
        pinPollingJob?.cancel()
        pinPollingJob = null
        refreshJob?.cancel()
        refreshJob = null
        storedState = SimklStoredAuthState()
        accessToken = null
        authenticationMethod = SimklAuthenticationMethod.BROWSER_REDIRECT
        publish()
    }

    fun selectedAuthenticationMethod(): SimklAuthenticationMethod {
        ensureLoaded()
        return authenticationMethod
    }

    fun setAuthenticationMethod(method: SimklAuthenticationMethod) {
        ensureLoaded()
        if (authenticationMethod == method) return
        pinPollingJob?.cancel()
        pinPollingJob = null
        authenticationMethod = method
        storedState = storedState.copy(authenticationMethod = method.storageValue)
        clearPendingAuthorization()
        persistMetadata()
        publish(isLoading = false, error = null)
    }

    fun pendingDeviceUserCode(): String? {
        ensureLoaded()
        return pendingPinUserCode(storedState.pendingAuthorizationState)
    }

    override fun removeStoredProfile(profileId: Int) {
        SimklAuthStorage.removeProfile(profileId)
    }

    fun snapshot(): SimklAuthUiState {
        ensureLoaded()
        return uiState.value
    }

    /**
     * Simkl only requires a client id (its OAuth flow is PKCE-based, no secret).
     * Sourced from the build-time generated [SimklConfig].
     */
    fun hasRequiredCredentials(): Boolean =
        RuntimeCredentials.isConfigured(SimklConfig.CLIENT_ID)

    fun onConnectRequested(): String? {
        ensureLoaded()
        if (!hasRequiredCredentials()) {
            publish(error = SimklAuthError.MISSING_CLIENT_ID)
            return null
        }

        pinPollingJob?.cancel()
        pinPollingJob = null
        clearPendingAuthorization()

        if (authenticationMethod == SimklAuthenticationMethod.DEVICE_CODE) {
            publish(isLoading = true, error = null)
            scope.launch { startPinAuthorization() }
            return null
        }

        val material = generateSimklPkceMaterial()
        SimklAuthStorage.saveCodeVerifier(material.verifier)
        storedState = storedState.copy(
            pendingAuthorizationState = material.state,
            pendingAuthorizationStartedAtEpochMs = SimklPlatformClock.nowEpochMs(),
        )
        persistMetadata()
        publish(error = null)
        return authorizationUrl(material)
    }

    private suspend fun startPinAuthorization() {
        val response = try {
            SimklApi.client.execute(
                SimklApiRequest(
                    method = SimklHttpMethod.GET,
                    path = "/oauth/pin",
                    query = mapOf("client_id" to SimklConfig.CLIENT_ID),
                    requiresAuthentication = false,
                    retryPolicy = SimklRetryPolicy.NEVER,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.w { "Simkl PIN request failed: ${error.message}" }
            failPinAuthorization()
            return
        }

        val parsed = runCatching { json.decodeFromString<SimklPinResponse>(response.body) }
            .getOrNull()
            ?.takeIf { it.userCode.isNotBlank() }
        if (parsed == null) {
            log.w { "Simkl PIN response was not usable" }
            failPinAuthorization()
            return
        }

        storedState = storedState.copy(
            pendingAuthorizationState = buildPendingPinState(parsed.userCode),
            pendingAuthorizationStartedAtEpochMs = SimklPlatformClock.nowEpochMs(),
        )
        persistMetadata()
        publish(isLoading = false, error = null)
        startPinPolling(
            userCode = parsed.userCode,
            intervalSeconds = parsed.interval,
            expiresInSeconds = parsed.expiresIn,
        )
    }

    private fun failPinAuthorization() {
        clearPendingAuthorization()
        persistMetadata()
        publish(isLoading = false, error = SimklAuthError.TOKEN_EXCHANGE_FAILED)
    }

    private fun startPinPolling(userCode: String, intervalSeconds: Int?, expiresInSeconds: Int?) {
        pinPollingJob?.cancel()
        pinPollingJob = scope.launch {
            val pollSeconds = (intervalSeconds ?: SIMKL_DEFAULT_PIN_POLL_INTERVAL_SECONDS)
                .coerceAtLeast(SIMKL_DEFAULT_PIN_POLL_INTERVAL_SECONDS)
            val expiresInMillis = (expiresInSeconds ?: SIMKL_DEFAULT_PIN_EXPIRES_IN_SECONDS)
                .coerceAtLeast(pollSeconds) * 1_000L
            val startedAt = SimklPlatformClock.nowEpochMs()

            while (
                pendingPinUserCode(storedState.pendingAuthorizationState) == userCode &&
                SimklPlatformClock.nowEpochMs() - startedAt < expiresInMillis
            ) {
                delay(pollSeconds * 1_000L)
                if (pollPin(userCode)) return@launch
            }

            if (pendingPinUserCode(storedState.pendingAuthorizationState) == userCode) {
                clearPendingAuthorization()
                persistMetadata()
                publish(isLoading = false, error = SimklAuthError.AUTHORIZATION_EXPIRED)
            }
        }
    }

    private suspend fun pollPin(userCode: String): Boolean {
        val response = try {
            SimklApi.client.execute(
                SimklApiRequest(
                    method = SimklHttpMethod.GET,
                    path = "/oauth/pin/$userCode",
                    query = mapOf("client_id" to SimklConfig.CLIENT_ID),
                    requiresAuthentication = false,
                    retryPolicy = SimklRetryPolicy.NEVER,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.d { "Simkl PIN poll failed: ${error.message}" }
            return false
        }

        val parsed = runCatching { json.decodeFromString<SimklPinPollResponse>(response.body) }
            .getOrNull() ?: return false
        val token = parsed.accessToken?.takeIf { parsed.isAuthorized } ?: return false

        applyAccessToken(token, expiresInSeconds = null)
        return true
    }

    fun pendingAuthorizationUrl(): String? {
        ensureLoaded()
        val state = storedState.pendingAuthorizationState?.takeIf(String::isNotBlank) ?: return null
        if (pendingPinUserCode(state) != null) return SIMKL_PIN_VERIFICATION_URL
        val verifier = SimklAuthStorage.loadCodeVerifier()?.takeIf(String::isNotBlank) ?: run {
            clearPendingAuthorization()
            persistMetadata()
            publish(error = SimklAuthError.AUTHORIZATION_EXPIRED)
            return null
        }
        if (isSimklAuthorizationExpired(
                startedAtEpochMs = storedState.pendingAuthorizationStartedAtEpochMs,
                nowEpochMs = SimklPlatformClock.nowEpochMs(),
            )
        ) {
            clearPendingAuthorization()
            persistMetadata()
            publish(error = SimklAuthError.AUTHORIZATION_EXPIRED)
            return null
        }
        return authorizationUrl(
            SimklPkceMaterial(
                verifier = verifier,
                challenge = SimklPkceCrypto.sha256(verifier.encodeToByteArray()).base64UrlWithoutPadding(),
                state = state,
            ),
        )
    }

    fun onCancelAuthorization() {
        ensureLoaded()
        pinPollingJob?.cancel()
        pinPollingJob = null
        clearPendingAuthorization()
        persistMetadata()
        publish(isLoading = false, error = null)
    }

    override fun handleAuthCallback(url: String): Boolean {
        ensureLoaded()
        if (pendingPinUserCode(storedState.pendingAuthorizationState) != null) return false
        return when (val callback = parseSimklAuthCallback(url, SimklConfig.REDIRECT_URI)) {
            SimklAuthCallback.NotSimkl -> false
            SimklAuthCallback.Invalid -> {
                clearPendingAuthorization()
                persistMetadata()
                publish(error = SimklAuthError.INVALID_CALLBACK)
                true
            }
            is SimklAuthCallback.AuthorizationCode -> {
                scope.launch { completeAuthorization(callback) }
                true
            }
        }
    }

    fun onDisconnectRequested() {
        ensureLoaded()
        refreshJob?.cancel()
        refreshJob = null
        accessToken = null
        SimklAuthStorage.saveAccessToken(null)
        SimklAuthStorage.saveRefreshToken(null)
        clearPendingAuthorization()
        storedState = SimklStoredAuthState()
        persistMetadata()
        SimklSyncRepository.clearLocalState()
        publish(error = null)
    }

    internal fun authorizedAccessToken(): String? {
        ensureLoaded()
        val token = accessToken?.takeIf(String::isNotBlank) ?: return null
        val expiresAt = storedState.tokenExpiresAtEpochMs
        if (expiresAt != null && SimklPlatformClock.nowEpochMs() >= expiresAt - TOKEN_EXPIRY_SKEW_MS) {
            invalidateCredentials(SimklAuthError.AUTHORIZATION_EXPIRED)
            return null
        }
        return token
    }

    /**
     * Called when the API answers 401. AUTH V2 access tokens expire after 7 days, so an
     * expired token is the common case and a genuinely revoked grant is the rare one:
     * try a refresh first, and only sign the user out when there is no refresh token or
     * the refresh itself is rejected.
     */
    internal fun onUnauthorizedResponse() {
        val refreshToken = SimklAuthStorage.loadRefreshToken()?.takeIf(String::isNotBlank)
        if (refreshToken == null) {
            invalidateCredentials(SimklAuthError.AUTHORIZATION_REVOKED)
            return
        }
        scope.launch {
            if (!refreshAccessToken(refreshToken)) {
                invalidateCredentials(SimklAuthError.AUTHORIZATION_REVOKED)
            }
        }
    }

    suspend fun refreshUserSettings(): String? {
        authorizedAccessToken() ?: return null
        return if (fetchAndStoreUserSettings()) storedState.username else null
    }

    internal suspend fun synchronizeUserSettings(activityWatermark: String?) {
        authorizedAccessToken() ?: return
        when (simklSettingsRefreshAction(storedState, activityWatermark)) {
            SimklSettingsRefreshAction.NONE -> Unit
            SimklSettingsRefreshAction.RECORD_WATERMARK -> {
                storedState = storedState.copy(settingsActivityWatermark = activityWatermark)
                persistMetadata()
            }
            SimklSettingsRefreshAction.FETCH -> {
                fetchAndStoreUserSettings(activityWatermark)
            }
        }
    }

    private suspend fun completeAuthorization(callback: SimklAuthCallback.AuthorizationCode) =
        authorizationMutex.withLock {
            publish(isLoading = true, error = null)
            val expectedState = storedState.pendingAuthorizationState
            val verifier = SimklAuthStorage.loadCodeVerifier()
            val isExpired = isSimklAuthorizationExpired(
                startedAtEpochMs = storedState.pendingAuthorizationStartedAtEpochMs,
                nowEpochMs = SimklPlatformClock.nowEpochMs(),
            )
            if (expectedState.isNullOrBlank() || verifier.isNullOrBlank() || isExpired) {
                clearPendingAuthorization()
                persistMetadata()
                publish(isLoading = false, error = SimklAuthError.AUTHORIZATION_EXPIRED)
                return@withLock
            }
            if (!constantTimeEquals(callback.state, expectedState)) {
                clearPendingAuthorization()
                persistMetadata()
                publish(isLoading = false, error = SimklAuthError.INVALID_CALLBACK_STATE)
                return@withLock
            }

            // AUTH V2: POST /oauth2/token with a form-encoded body. NEVER retried,
            // because Simkl consumes the authorization code even when the exchange
            // fails -- a retry would burn a second trip through the consent screen
            // for no possible gain.
            val response = try {
                SimklApi.client.execute(
                    SimklApiRequest(
                        method = SimklHttpMethod.POST,
                        path = SIMKL_TOKEN_PATH,
                        body = buildSimklTokenForm(
                            mapOf(
                                "grant_type" to "authorization_code",
                                "client_id" to SimklConfig.CLIENT_ID,
                                "code" to callback.code,
                                // Must match the redirect_uri sent to authorize, byte for byte.
                                "redirect_uri" to SimklConfig.REDIRECT_URI,
                                "code_verifier" to verifier,
                            ),
                        ),
                        formEncoded = true,
                        requiresAuthentication = false,
                        retryPolicy = SimklRetryPolicy.NEVER,
                    ),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.w { "Simkl token exchange failed: ${error.message}" }
                clearPendingAuthorization()
                persistMetadata()
                publish(isLoading = false, error = SimklAuthError.TOKEN_EXCHANGE_FAILED)
                return@withLock
            }
            val token = runCatching { json.decodeFromString<SimklTokenResponse>(response.body) }
                .getOrNull()
                ?.takeIf { it.accessToken.isNotBlank() }
            if (token == null) {
                clearPendingAuthorization()
                persistMetadata()
                publish(isLoading = false, error = SimklAuthError.INVALID_TOKEN_RESPONSE)
                return@withLock
            }

            applyAccessToken(token.accessToken, token.expiresIn, token.refreshToken)
        }

    private suspend fun applyAccessToken(
        token: String,
        expiresInSeconds: Long?,
        refreshToken: String? = null,
    ) {
        accessToken = token
        SimklAuthStorage.saveAccessToken(token)
        // Exactly what this grant issued. A null clears any refresh token left over
        // from an earlier session rather than silently reusing a stale credential.
        SimklAuthStorage.saveRefreshToken(refreshToken?.takeIf(String::isNotBlank))
        clearPendingAuthorization()
        storedState = storedState.copy(
            tokenExpiresAtEpochMs = expiresInSeconds
                ?.takeIf { seconds -> seconds > 0L }
                ?.let { seconds -> SimklPlatformClock.nowEpochMs() + seconds * 1_000L },
        )
        persistMetadata()
        scheduleTokenRefresh()
        publish(isLoading = false, error = null)
        fetchAndStoreUserSettings()
        SimklSyncRepository.refreshAsync(
            intent = TrackingRefreshIntent.INVALIDATED,
            origin = SimklRefreshOrigin.AUTHORIZATION,
        )
    }

    /**
     * AUTH V2 access tokens last 7 days, so a connected user would otherwise be sent
     * back through the consent screen every week. Refresh shortly before expiry.
     * Nothing is scheduled when there is no refresh token (V1-era sessions), which
     * leaves the previous expiry behaviour intact.
     */
    private fun scheduleTokenRefresh() {
        refreshJob?.cancel()
        val refreshToken = SimklAuthStorage.loadRefreshToken()?.takeIf(String::isNotBlank) ?: return
        val expiresAt = storedState.tokenExpiresAtEpochMs ?: return
        val delayMs = expiresAt - SimklPlatformClock.nowEpochMs() - TOKEN_EXPIRY_SKEW_MS
        refreshJob = scope.launch {
            if (delayMs > 0L) delay(delayMs)
            refreshAccessToken(refreshToken)
        }
    }

    /**
     * Exchanges a refresh token for a new access token. Returns true on success.
     * Failures are logged and reported as false; only the caller that has no other
     * way forward turns that into a signed-out state.
     */
    private suspend fun refreshAccessToken(refreshToken: String): Boolean = refreshMutex.withLock {
        if (accessToken == null) return@withLock false
        val response = try {
            SimklApi.client.execute(
                SimklApiRequest(
                    method = SimklHttpMethod.POST,
                    path = SIMKL_TOKEN_PATH,
                    body = buildSimklTokenForm(
                        mapOf(
                            "grant_type" to "refresh_token",
                            "client_id" to SimklConfig.CLIENT_ID,
                            "refresh_token" to refreshToken,
                        ),
                    ),
                    formEncoded = true,
                    requiresAuthentication = false,
                    retryPolicy = SimklRetryPolicy.NEVER,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.w { "Simkl token refresh failed: ${error.message}" }
            return@withLock false
        }
        val token = runCatching { json.decodeFromString<SimklTokenResponse>(response.body) }
            .getOrNull()
            ?.takeIf { it.accessToken.isNotBlank() }
        if (token == null) {
            log.w { "Simkl token refresh returned an unusable response" }
            return@withLock false
        }
        // Simkl may rotate the refresh token. Keep the current one when it does not.
        val nextRefreshToken = token.refreshToken?.takeIf(String::isNotBlank) ?: refreshToken
        accessToken = token.accessToken
        SimklAuthStorage.saveAccessToken(token.accessToken)
        SimklAuthStorage.saveRefreshToken(nextRefreshToken)
        storedState = storedState.copy(
            tokenExpiresAtEpochMs = token.expiresIn
                ?.takeIf { seconds -> seconds > 0L }
                ?.let { seconds -> SimklPlatformClock.nowEpochMs() + seconds * 1_000L },
        )
        persistMetadata()
        scheduleTokenRefresh()
        publish(error = null)
        true
    }

    private suspend fun fetchAndStoreUserSettings(activityWatermark: String? = null): Boolean {
        val response = try {
            SimklApi.client.execute(
                SimklApiRequest(
                    method = SimklHttpMethod.POST,
                    path = "/users/settings",
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.w { "Failed to fetch Simkl user settings: ${error.message}" }
            return false
        }
        val settings = runCatching { json.decodeFromString<SimklUserSettingsResponse>(response.body) }
            .getOrNull() ?: return false
        storedState = storedState.copy(
            username = settings.user?.name,
            accountId = settings.account?.id,
            hasFetchedUserSettings = true,
            settingsActivityWatermark = activityWatermark ?: storedState.settingsActivityWatermark,
        )
        persistMetadata()
        publish(error = null)
        return true
    }

    private fun loadFromDisk() {
        hasLoaded = true
        storedState = SimklAuthStorage.loadMetadataPayload()
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { payload ->
                runCatching { json.decodeFromString<SimklStoredAuthState>(payload) }
                    .onFailure { error -> log.w { "Failed to parse Simkl auth metadata: ${error.message}" } }
                    .getOrNull()
            }
            ?: SimklStoredAuthState()
        authenticationMethod = SimklAuthenticationMethod.fromStorageValue(storedState.authenticationMethod)
        accessToken = SimklAuthStorage.loadAccessToken()?.takeIf(String::isNotBlank)
        if (accessToken != null && storedState.tokenExpiresAtEpochMs?.let { expiresAt ->
                SimklPlatformClock.nowEpochMs() >= expiresAt - TOKEN_EXPIRY_SKEW_MS
            } == true
        ) {
            // AUTH V2 access tokens live 7 days but refresh tokens live 180, so an
            // expired access token at startup is normally recoverable. Only discard the
            // session when there is no refresh token to recover it with.
            val refreshToken = SimklAuthStorage.loadRefreshToken()?.takeIf(String::isNotBlank)
            if (refreshToken == null) {
                accessToken = null
                SimklAuthStorage.saveAccessToken(null)
                storedState = SimklStoredAuthState()
                persistMetadata()
            } else {
                scope.launch {
                    if (!refreshAccessToken(refreshToken)) {
                        invalidateCredentials(SimklAuthError.AUTHORIZATION_EXPIRED)
                    }
                }
            }
        } else {
            // Not expired (or no token at all): arm the pre-expiry refresh timer so a
            // long-running app does not hit the 7-day wall.
            scheduleTokenRefresh()
        }
        if (storedState.hasPendingAuthorization && isSimklAuthorizationExpired(
                startedAtEpochMs = storedState.pendingAuthorizationStartedAtEpochMs,
                nowEpochMs = SimklPlatformClock.nowEpochMs(),
            )
        ) {
            clearPendingAuthorization()
            persistMetadata()
        }
        publish(error = null)
    }

    private fun invalidateCredentials(error: SimklAuthError) {
        refreshJob?.cancel()
        refreshJob = null
        accessToken = null
        SimklAuthStorage.saveAccessToken(null)
        // A signed-out session must not leave a 180-day refresh token on disk.
        SimklAuthStorage.saveRefreshToken(null)
        clearPendingAuthorization()
        storedState = SimklStoredAuthState()
        persistMetadata()
        SimklSyncRepository.clearLocalState()
        publish(isLoading = false, error = error)
    }

    private fun clearPendingAuthorization() {
        SimklAuthStorage.saveCodeVerifier(null)
        storedState = storedState.copy(
            pendingAuthorizationState = null,
            pendingAuthorizationStartedAtEpochMs = null,
        )
    }

    private fun persistMetadata() {
        SimklAuthStorage.saveMetadataPayload(json.encodeToString(storedState))
    }

    private fun publish(
        isLoading: Boolean = _uiState.value.isLoading,
        error: SimklAuthError? = _uiState.value.error,
    ) {
        val authenticated = !accessToken.isNullOrBlank()
        _isAuthenticated.value = authenticated
        _uiState.value = SimklAuthUiState(
            mode = when {
                authenticated -> SimklConnectionMode.CONNECTED
                storedState.hasPendingAuthorization -> SimklConnectionMode.AWAITING_APPROVAL
                else -> SimklConnectionMode.DISCONNECTED
            },
            credentialsConfigured = hasRequiredCredentials(),
            isLoading = isLoading,
            username = storedState.username,
            accountId = storedState.accountId,
            tokenExpiresAtEpochMs = storedState.tokenExpiresAtEpochMs,
            pendingAuthorizationStartedAtEpochMs = storedState.pendingAuthorizationStartedAtEpochMs,
            error = error,
        )
    }

    private fun authorizationUrl(material: SimklPkceMaterial): String =
        buildSimklAuthorizationUrl(
            clientId = SimklConfig.CLIENT_ID,
            redirectUri = SimklConfig.REDIRECT_URI,
            appName = SimklConfig.APP_NAME,
            appVersion = simklAppVersion,
            material = material,
        )

    private const val TOKEN_EXPIRY_SKEW_MS = 60_000L
}

@Serializable
private data class SimklTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String? = null,
    val scope: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
)

@Serializable
private data class SimklUserSettingsResponse(
    val user: SimklUser? = null,
    val account: SimklAccount? = null,
)

@Serializable
private data class SimklUser(
    val name: String? = null,
)

@Serializable
private data class SimklAccount(
    val id: Long? = null,
)
