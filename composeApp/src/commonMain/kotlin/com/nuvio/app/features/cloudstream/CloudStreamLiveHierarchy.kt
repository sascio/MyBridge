package com.nuvio.app.features.cloudstream

/**
 * Runtime-shaped Live TV navigation tree.
 *
 * CloudStream does not have one universal sub-provider API. A registered
 * MainAPI is therefore the provider boundary, and homepage sections are kept
 * as arbitrary-depth navigation nodes. The model is deliberately data-only:
 * it does not infer or recognize provider names.
 */
enum class CloudStreamLiveNodeType {
    ROOT,
    EXTENSION,
    PROVIDER,
    SUB_PROVIDER,
    SECTION,
    CATEGORY,
    CHANNEL,
}

data class CloudStreamLiveNode(
    val repositoryId: String,
    val extensionId: String,
    val providerId: String,
    val nodeId: String,
    val parentNodeId: String?,
    val nodeType: CloudStreamLiveNodeType,
    val title: String,
    val iconUrl: String? = null,
    val children: List<CloudStreamLiveNode> = emptyList(),
    val catalogItem: CloudStreamLiveCatalogItem? = null,
    val errorMessage: String? = null,
) {
    val isSelectableNavigation: Boolean
        get() = nodeType != CloudStreamLiveNodeType.CHANNEL && children.isNotEmpty()

    val channels: List<CloudStreamLiveNode>
        get() = if (nodeType == CloudStreamLiveNodeType.CHANNEL) {
            listOf(this)
        } else {
            children.flatMap { it.channels }
        }
}

data class CloudStreamLiveHierarchy(
    val roots: List<CloudStreamLiveNode> = emptyList(),
    val errors: List<CloudStreamLiveNode> = emptyList(),
) {
    val channels: List<CloudStreamLiveNode>
        get() = roots.flatMap { it.channels }

    val isEmpty: Boolean
        get() = roots.isEmpty() && errors.isEmpty()

    val providers: List<CloudStreamLiveNode>
        get() = roots.flatMap { root ->
            when (root.nodeType) {
                CloudStreamLiveNodeType.PROVIDER -> listOf(root)
                CloudStreamLiveNodeType.EXTENSION -> root.children.filter {
                    it.nodeType == CloudStreamLiveNodeType.PROVIDER
                }
                else -> root.children.filter { it.nodeType == CloudStreamLiveNodeType.PROVIDER }
            }
        }

    fun node(nodeId: String?): CloudStreamLiveNode? {
        if (nodeId == null) return null
        fun find(nodes: List<CloudStreamLiveNode>): CloudStreamLiveNode? {
            nodes.forEach { candidate ->
                if (candidate.nodeId == nodeId) return candidate
                find(candidate.children)?.let { return it }
            }
            return null
        }
        return find(roots) ?: errors.firstOrNull { it.nodeId == nodeId }
    }

    companion object {
        /**
         * Catalog-only fallback used by common and test backends. Android's
         * executor supplies stable provider/repository identities before this
         * method is called; display names are only labels.
         */
        fun fromCatalog(items: List<CloudStreamLiveCatalogItem>): CloudStreamLiveHierarchy {
            val extensionGroups = items.groupBy { item ->
                listOf(item.repositoryId, item.extensionId)
            }
            val roots = extensionGroups.values.mapNotNull { extensionItems ->
                val first = extensionItems.firstOrNull() ?: return@mapNotNull null
                val extensionNodeId = CloudStreamLiveIdentity.nodeId(
                    repositoryId = first.repositoryId,
                    extensionId = first.extensionId,
                    providerId = "extension",
                    parentNodeId = null,
                    title = first.extensionName.ifBlank { first.extensionId.ifBlank { first.providerName } },
                    ordinal = "extension",
                )
                val providerNodes = extensionItems
                    .groupBy { item ->
                        listOf(
                            item.repositoryId,
                            item.extensionId,
                            item.providerId.ifBlank { item.providerName },
                        )
                    }
                    .values
                    .mapNotNull { providerItems ->
                        providerNode(providerItems, extensionNodeId)
                    }
                    .sortedBy { it.title.lowercase() }
                CloudStreamLiveNode(
                    repositoryId = first.repositoryId,
                    extensionId = first.extensionId,
                    providerId = "extension",
                    nodeId = extensionNodeId,
                    parentNodeId = null,
                    nodeType = CloudStreamLiveNodeType.EXTENSION,
                    title = first.extensionName.ifBlank { first.extensionId.ifBlank { first.providerName } },
                    iconUrl = first.poster,
                    children = providerNodes,
                )
            }.sortedBy { it.title.lowercase() }
            return CloudStreamLiveHierarchy(roots = roots)
        }

        private fun providerNode(
            items: List<CloudStreamLiveCatalogItem>,
            extensionNodeId: String,
        ): CloudStreamLiveNode? {
            val first = items.firstOrNull() ?: return null
            val providerId = first.providerId.ifBlank { first.providerName }
            val providerNodeId = CloudStreamLiveIdentity.nodeId(
                repositoryId = first.repositoryId,
                extensionId = first.extensionId,
                providerId = providerId,
                parentNodeId = extensionNodeId,
                title = first.providerName,
                ordinal = "provider",
            )
            return CloudStreamLiveNode(
                repositoryId = first.repositoryId,
                extensionId = first.extensionId,
                providerId = providerId,
                nodeId = providerNodeId,
                parentNodeId = extensionNodeId,
                nodeType = CloudStreamLiveNodeType.PROVIDER,
                title = first.providerName,
                iconUrl = first.poster,
                children = buildSectionTree(items, providerId, providerNodeId),
            )
        }

        /** Builds a prefix-sharing tree, not one duplicated node per full path. */
        private fun buildSectionTree(
            items: List<CloudStreamLiveCatalogItem>,
            providerId: String,
            providerNodeId: String,
        ): List<CloudStreamLiveNode> {
            val paths = items.associateWith { item ->
                item.sectionPath
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .dropWhile { it.equals(item.providerName, ignoreCase = true) }
                    .ifEmpty { listOf(item.category ?: "Channels") }
            }
            return buildSectionLevel(
                entries = paths.entries.toList(),
                depth = 0,
                parentNodeId = providerNodeId,
                providerId = providerId,
            )
        }

        private fun buildSectionLevel(
            entries: List<Map.Entry<CloudStreamLiveCatalogItem, List<String>>>,
            depth: Int,
            parentNodeId: String,
            providerId: String,
        ): List<CloudStreamLiveNode> {
            val grouped = entries.groupBy { it.value.getOrNull(depth) ?: "Channels" }
            return grouped.entries.mapIndexed { ordinal, (title, group) ->
                val first = group.first().key
                val nodeId = CloudStreamLiveIdentity.nodeId(
                    repositoryId = first.repositoryId,
                    extensionId = first.extensionId,
                    providerId = providerId,
                    parentNodeId = parentNodeId,
                    title = title,
                    ordinal = ordinal.toString(),
                )
                val hasDeeperPath = group.any { it.value.size > depth + 1 }
                val children = if (hasDeeperPath) {
                    val nested = buildSectionLevel(group, depth + 1, nodeId, providerId)
                    val leafItems = group.filter { it.value.size <= depth + 1 }
                    if (leafItems.isEmpty()) nested else {
                        nested + channelNodes(leafItems.map { it.key }, nodeId, providerId)
                    }
                } else {
                    channelNodes(group.map { it.key }, nodeId, providerId)
                }
                CloudStreamLiveNode(
                    repositoryId = first.repositoryId,
                    extensionId = first.extensionId,
                    providerId = providerId,
                    nodeId = nodeId,
                    parentNodeId = parentNodeId,
                    nodeType = if (depth == 0) CloudStreamLiveNodeType.SECTION else CloudStreamLiveNodeType.CATEGORY,
                    title = title,
                    children = children,
                )
            }.sortedBy { it.title.lowercase() }
        }

        private fun channelNodes(
            items: List<CloudStreamLiveCatalogItem>,
            parentNodeId: String,
            providerId: String,
        ): List<CloudStreamLiveNode> = items.map { item ->
            CloudStreamLiveNode(
                repositoryId = item.repositoryId,
                extensionId = item.extensionId,
                providerId = providerId,
                nodeId = CloudStreamLiveIdentity.channelNodeId(item),
                parentNodeId = parentNodeId,
                nodeType = CloudStreamLiveNodeType.CHANNEL,
                title = item.title,
                iconUrl = item.poster,
                catalogItem = item,
            )
        }.sortedBy { it.title.lowercase() }
    }
}

internal object CloudStreamLiveIdentity {
    fun providerId(repositoryId: String, extensionId: String, className: String, name: String, mainUrl: String): String =
        "provider:" + stableHash(listOf(repositoryId, extensionId, className, name, mainUrl))

    fun nodeId(
        repositoryId: String,
        extensionId: String,
        providerId: String,
        parentNodeId: String?,
        title: String,
        ordinal: String,
    ): String = "node:" + stableHash(listOf(repositoryId, extensionId, providerId, parentNodeId.orEmpty(), title, ordinal))

    fun channelNodeId(item: CloudStreamLiveCatalogItem): String =
        "channel:" + stableHash(
            listOf(
                item.repositoryId,
                item.extensionId,
                item.providerId,
                item.sectionPath.joinToString("\u001e"),
                item.url,
                item.title,
            ),
        )

    private fun stableHash(parts: List<String>): String {
        var hash = -0x340d631b8c467c5L
        parts.joinToString("\u001f").forEach { character ->
            hash = hash xor character.code.toLong()
            hash *= 0x100000001b3L
        }
        return hash.toULong().toString(16)
    }
}
