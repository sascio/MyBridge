package com.nuvio.app.features.cloudstream

import androidx.appcompat.app.AppCompatActivity
import com.nuvio.app.MainActivity
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The dynamic CloudStream ABI receives the actual host Activity. This must stay
 * an AppCompatActivity: several upstream helpers and providers use AppCompat
 * APIs for WebView/settings/UI work.
 */
class CloudStreamActivityCompatibilityTest {
    @Test
    fun streamBridgeMainActivityIsAnAppCompatActivity() {
        assertTrue(
            AppCompatActivity::class.java.isAssignableFrom(MainActivity::class.java),
            "CloudStream must be hosted by the real AppCompatActivity hierarchy, not a ContextWrapper.",
        )
    }
}
