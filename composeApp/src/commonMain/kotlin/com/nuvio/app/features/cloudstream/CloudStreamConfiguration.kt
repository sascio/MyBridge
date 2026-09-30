package com.nuvio.app.features.cloudstream

/** Generic capability result for the real CloudStream plugin configuration hook. */
enum class CloudStreamConfigurationStatus {
    CONFIGURABLE,
    NOT_CONFIGURABLE,
    CONFIGURATION_UNAVAILABLE,
    CONFIGURATION_FAILED,
}

data class CloudStreamConfigurationCapability(
    val status: CloudStreamConfigurationStatus,
    val configurationType: String? = null,
    val pluginId: String,
    val providerId: String? = null,
    val providerClass: String? = null,
    val requiredActivityType: String? = null,
    val requiredContext: String? = null,
    val message: String? = null,
) {
    val isConfigurable: Boolean
        get() = status == CloudStreamConfigurationStatus.CONFIGURABLE
}
