package com.nuvio.app.features.mdblist

import com.nuvio.app.features.tracking.TrackingExternalIds
import com.nuvio.app.features.tracking.TrackingMediaKind
import com.nuvio.app.features.tracking.TrackingRatingException
import com.nuvio.app.features.tracking.TrackingRatingScope
import com.nuvio.app.features.tracking.TrackingRatingTarget
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class MdbListPersonalRatingMutationTest {
    private val target = TrackingRatingTarget(
        TrackingRatingScope.MOVIE, TrackingMediaKind.MOVIE, TrackingExternalIds(imdb = "tt1"),
    )

    @Test
    fun removalErrorsAreNotReportedAsSuccess() = runTest {
        val harness = MdbListTestHarness()
        harness.connected()
        harness.reply(body = """{"errors":[{"message":"unable to remove"}]}""")
        val provider = MdbListRatingsProvider(harness.api, harness.store)
        expectMdbListFailure<TrackingRatingException> { provider.removeRating(1, target) }
        assertEquals("/sync/ratings/remove", harness.engine.requests.single().path)
    }

    @Test
    fun successfulRemovalIsAccepted() = runTest {
        val harness = MdbListTestHarness()
        harness.connected()
        harness.reply(body = """{"deleted":{"movies":1},"not_found":{"movies":[]},"errors":[]}""")
        MdbListRatingsProvider(harness.api, harness.store).removeRating(1, target)
        assertEquals("/sync/ratings/remove", harness.engine.requests.single().path)
    }
}
