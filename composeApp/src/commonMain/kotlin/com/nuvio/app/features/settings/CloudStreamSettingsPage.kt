package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.Modifier
import com.nuvio.app.features.cloudstream.CloudStreamExtensionsPageContent

internal fun LazyListScope.cloudStreamSettingsContent() {
    item {
        CloudStreamExtensionsPageContent(
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
