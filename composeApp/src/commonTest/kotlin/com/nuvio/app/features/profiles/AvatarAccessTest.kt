package com.nuvio.app.features.profiles

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AvatarAccessTest {
    @Test
    fun supporterAvatarsAreOnlyPublishedWithAccess() {
        val standard = AvatarCatalogItem(id = "standard", storagePath = "standard.png")
        val supporter = AvatarCatalogItem(
            id = "supporter-gold",
            storagePath = "gold.png",
            localImageUrl = "file:///cache/supporter-gold.png",
            memberOnly = true,
        )

        assertEquals(
            listOf(standard),
            availableAvatarCatalog(listOf(standard), listOf(supporter), hasMemberAccess = false),
        )
        assertEquals(
            listOf(standard, supporter),
            availableAvatarCatalog(listOf(standard), listOf(supporter), hasMemberAccess = true),
        )
    }

    @Test
    fun pickerStaysLoadingUntilCatalogResolves() {
        assertEquals(
            AvatarPickerStatus.Loading,
            avatarPickerStatus(isLoading = false, hasLoaded = false, loadFailed = false, itemCount = 0),
        )
        assertEquals(
            AvatarPickerStatus.Loading,
            avatarPickerStatus(isLoading = true, hasLoaded = false, loadFailed = false, itemCount = 0),
        )
    }

    @Test
    fun pickerShowsErrorOrEmptyInsteadOfInfiniteLoading() {
        assertEquals(
            AvatarPickerStatus.Failed,
            avatarPickerStatus(isLoading = false, hasLoaded = true, loadFailed = true, itemCount = 0),
        )
        assertEquals(
            AvatarPickerStatus.Empty,
            avatarPickerStatus(isLoading = false, hasLoaded = true, loadFailed = false, itemCount = 0),
        )
        assertEquals(
            AvatarPickerStatus.Ready,
            avatarPickerStatus(isLoading = false, hasLoaded = true, loadFailed = true, itemCount = 1),
        )
    }

    @Test
    fun avatarStorageUrlRequiresBackendAndKeepsAbsolutePaths() {
        assertNull(avatarStorageUrl("character/one.png", backendUrl = ""))
        assertNull(avatarStorageUrl("   ", backendUrl = "https://api.nuvio.tv"))
        assertEquals(
            "https://api.nuvio.tv/storage/v1/object/public/avatars/character/one.png",
            avatarStorageUrl("character/one.png", backendUrl = "https://api.nuvio.tv/"),
        )
        assertEquals(
            "https://cdn.example/avatar.png",
            avatarStorageUrl("https://cdn.example/avatar.png", backendUrl = ""),
        )
    }

    @Test
    fun supporterAvatarUsesAuthenticatedLocalAsset() {
        val supporter = AvatarCatalogItem(
            id = "supporter-gold",
            storagePath = "private/gold.png",
            localImageUrl = "file:///cache/supporter-gold.png",
            memberOnly = true,
        )

        assertEquals("file:///cache/supporter-gold.png", avatarImageUrl(supporter))
        assertNull(avatarImageUrl(supporter.copy(localImageUrl = null)))
    }
}
