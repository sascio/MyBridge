package com.streambridge.app.ui.sources

import com.streambridge.app.addon.SourceResult
import com.streambridge.app.addon.SourceStatus
import com.streambridge.app.addon.model.StreamOption
import com.streambridge.app.player.PlaybackRequest

/**
 * A selectable entry in the horizontal source selector:
 * ALL, or one source that actually returned usable streams.
 */
data class SourceTab(
    val id: String,
    val label: String,
    /** Playable stream count behind this tab. */
    val count: Int
)

/** One provider group inside the stream list. */
data class SourceGroup(
    val sourceId: String,
    val title: String,
    val streams: List<StreamOption>
)

/**
 * Source-selection state for ONE media item (movie or episode). It is
 * a pure function of the latest per-source results: the selector tabs
 * are generated dynamically from ACTUAL results — sources that return
 * nothing usable simply have no tab for this request, without being
 * uninstalled or disabled; the next request evaluates them again.
 */
data class SourceSelectionUiState(
    val request: PlaybackRequest,
    /** Latest result per source, in stable source order. */
    val results: List<SourceResult> = emptyList(),
    /** Selected selector tab: [TAB_ALL] or a source id. */
    val selectedTab: String = TAB_ALL,
    /** Identifies the current request pass; bumped by every restart. */
    val serial: Int = 0
) {
    companion object {
        const val TAB_ALL = "all"
    }

    val running: Boolean get() = results.any { it.status is SourceStatus.Loading }
    val finished: Boolean get() = results.isNotEmpty() && !running

    /** Sources that returned at least one playable stream, in stable order. */
    val usableResults: List<SourceResult> get() = results.filter { it.usable }

    /** Selector tabs: ALL first, then every source with usable results. */
    val tabs: List<SourceTab>
        get() = buildList {
            add(SourceTab(TAB_ALL, "ALL", totalStreams))
            usableResults.forEach { result ->
                add(SourceTab(result.sourceId, result.name, result.playableStreams.size))
            }
        }

    /** Groups shown for the selected tab (ALL combines usable sources). */
    val groups: List<SourceGroup>
        get() = when (selectedTab) {
            TAB_ALL -> usableResults.map { result ->
                SourceGroup(result.sourceId, result.name, result.playableStreams)
            }
            else -> usableResults
                .filter { result -> result.sourceId == selectedTab }
                .map { result -> SourceGroup(result.sourceId, result.name, result.playableStreams) }
        }

    val totalStreams: Int get() = usableResults.sumOf { it.playableStreams.size }
    val hasStreams: Boolean get() = totalStreams > 0

    /** Terminal sources without usable streams — hidden from the selector. */
    val hiddenCount: Int get() = results.count { !it.usable && it.status.isTerminal }

    /** Loading sources that have not settled yet (for progress hints). */
    val pendingCount: Int get() = results.count { it.status is SourceStatus.Loading }

    /**
     * Selects a tab. Only ALL and sources that are currently usable can
     * be selected, so a stale tab id is a no-op.
     */
    fun withSelectedTab(tabId: String): SourceSelectionUiState =
        if (tabId == TAB_ALL || results.any { it.sourceId == tabId && it.usable }) {
            copy(selectedTab = tabId)
        } else {
            this
        }
}

/**
 * Starts a fresh request pass: every source goes back to Loading, the
 * tabs are rebuilt from scratch (progressively, as results arrive) and
 * the selection resets to ALL. [loading] placeholders carry the stable
 * source order; [serial] tags this pass so stale events get dropped.
 */
internal fun SourceSelectionUiState.restart(
    loading: List<SourceResult>,
    serial: Int
): SourceSelectionUiState = copy(
    results = loading,
    selectedTab = SourceSelectionUiState.TAB_ALL,
    serial = serial
)

/**
 * Applies one progressive source result. Events from a superseded pass
 * (after a reload) are dropped; the current pass updates in place —
 * the screen never rebuilds from zero while a request is running.
 */
internal fun SourceSelectionUiState.applyResult(
    result: SourceResult,
    expectedSerial: Int
): SourceSelectionUiState {
    if (serial != expectedSerial) return this
    return copy(
        results = results.map { existing ->
            if (existing.sourceId == result.sourceId) result else existing
        }
    )
}
