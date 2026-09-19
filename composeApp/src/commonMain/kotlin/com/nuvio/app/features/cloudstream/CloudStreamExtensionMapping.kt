package com.nuvio.app.features.cloudstream

/**
 * Pure derivation of extensions, sources and counters.
 *
 * Kept free of storage and networking so the whole hierarchy — including the
 * honesty rules — is unit-testable with deterministic fixtures.
 * [CloudStreamExtensionsRepository] supplies persistence and I/O.
 */
internal object CloudStreamExtensionMapping {

    /**
     * Builds an extension and the sources it exposes.
     *
     * CloudStream declares supported content types (`tvTypes`) per plugin, so
     * each declared type becomes its own source entry. This is what keeps an
     * Extension and its Sources separate concepts rather than one flat card.
     * A plugin declaring no types still exposes a single source so the
     * hierarchy stays uniform.
     */
    fun toExtension(
        plugin: CloudStreamPlugin,
        repositoryUrl: String,
        states: Map<String, CloudStreamSourceState> = emptyMap(),
        configuration: Map<String, String> = emptyMap(),
    ): CloudStreamExtension {
        val declaredTypes = plugin.tvTypes.filter { it.isNotBlank() }
        val sources = if (declaredTypes.isEmpty()) {
            listOf(buildSource(plugin, plugin.displayName, null, states, configuration))
        } else {
            declaredTypes.map { type ->
                buildSource(plugin, "${plugin.displayName} · $type", type, states, configuration)
            }
        }
        return CloudStreamExtension(
            plugin = plugin,
            repositoryUrl = repositoryUrl,
            sources = sources,
        )
    }

    fun sourceId(plugin: CloudStreamPlugin, contentType: String?): String =
        if (contentType == null) plugin.id else "${plugin.id}::$contentType"

    private fun buildSource(
        plugin: CloudStreamPlugin,
        name: String,
        contentType: String?,
        states: Map<String, CloudStreamSourceState>,
        configuration: Map<String, String>,
    ): CloudStreamSource {
        val id = sourceId(plugin, contentType)
        val persisted = states[id]
        // An unsupported plugin can never be installed or enabled, regardless
        // of what happens to be persisted.
        val canActivate = plugin.isExecutable
        return CloudStreamSource(
            id = id,
            name = name,
            contentType = contentType,
            language = plugin.language,
            compatibility = plugin.compatibility,
            compatibilityReason = plugin.compatibilityReason,
            installed = canActivate && persisted?.installed == true,
            enabled = canActivate && persisted?.enabled == true,
            configuration = supportedConfiguration(id, configuration),
        )
    }

    /**
     * Configuration a source genuinely supports.
     *
     * CloudStream publishes no declarative configuration schema in `repo.json`
     * or `plugins.json`, and the runtime exposes no enumerable settings
     * contract for a loaded plugin. There is therefore nothing StreamBridge can
     * honestly present as configurable, so returning an empty list correctly
     * hides the Configure action rather than showing a control that cannot be
     * wired to anything real.
     */
    fun supportedConfiguration(
        sourceId: String,
        configuration: Map<String, String>,
    ): List<CloudStreamConfigField> = emptyList()

    /** Live Overview counters derived from real state. */
    fun overview(extensions: List<CloudStreamExtension>): CloudStreamOverview =
        CloudStreamOverview(
            extensionCount = extensions.size,
            activeCount = extensions.count { it.isActive },
            catalogCount = extensions.sumOf { it.sourceCount },
        )
}
