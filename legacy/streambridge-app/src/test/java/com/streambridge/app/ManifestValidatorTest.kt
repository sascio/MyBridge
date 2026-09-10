package com.streambridge.app

import com.streambridge.app.addon.ManifestValidator
import com.streambridge.app.addon.model.AddonCatalog
import com.streambridge.app.addon.model.AddonManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestValidatorTest {

    private fun fullManifest(
        id: String = "com.example.addon",
        name: String = "Example",
        version: String = "1.0.0",
        types: List<String> = listOf("movie", "series"),
        resources: List<String> = listOf("catalog", "meta", "stream"),
        catalogs: List<AddonCatalog> = listOf(AddonCatalog(type = "movie", id = "top", name = "Top"))
    ) = AddonManifest(
        id = id,
        name = name,
        version = version,
        types = types,
        resources = resources,
        catalogs = catalogs
    )

    @Test
    fun `complete manifest is valid`() {
        assertEquals(ManifestValidator.Result.Valid, ManifestValidator.validate(fullManifest()))
    }

    @Test
    fun `manifest without id is invalid`() {
        val result = ManifestValidator.validate(fullManifest(id = ""))
        assertTrue(result is ManifestValidator.Result.Invalid)
    }

    @Test
    fun `manifest without name is invalid`() {
        assertTrue(ManifestValidator.validate(fullManifest(name = "")) is ManifestValidator.Result.Invalid)
    }

    @Test
    fun `manifest without version is invalid`() {
        assertTrue(
            ManifestValidator.validate(fullManifest(version = "")) is ManifestValidator.Result.Invalid
        )
    }

    @Test
    fun `manifest without types is invalid`() {
        assertTrue(
            ManifestValidator.validate(fullManifest(types = emptyList())) is ManifestValidator.Result.Invalid
        )
    }

    @Test
    fun `manifest without resources is invalid`() {
        assertTrue(
            ManifestValidator.validate(fullManifest(resources = emptyList())) is ManifestValidator.Result.Invalid
        )
    }

    @Test
    fun `manifest with only unknown resources is invalid`() {
        assertTrue(
            ManifestValidator.validate(fullManifest(resources = listOf("weather"))) is
                ManifestValidator.Result.Invalid
        )
    }

    @Test
    fun `catalog resource without catalogs is invalid`() {
        assertTrue(
            ManifestValidator.validate(fullManifest(catalogs = emptyList())) is
                ManifestValidator.Result.Invalid
        )
    }

    @Test
    fun `browsable catalogs flag`() {
        assertTrue(ManifestValidator.hasBrowsableCatalogs(fullManifest()))
        assertFalse(
            ManifestValidator.hasBrowsableCatalogs(fullManifest(resources = listOf("stream")))
        )
    }
}
