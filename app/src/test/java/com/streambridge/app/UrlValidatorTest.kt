package com.streambridge.app

import com.streambridge.app.addon.HttpAddonApi
import com.streambridge.app.addon.UrlValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlValidatorTest {

    @Test
    fun `valid https url passes`() {
        val result = UrlValidator.validate("https://example.com/manifest.json")
        assertTrue(result is UrlValidator.Result.Valid)
        assertEquals(
            "https://example.com/manifest.json",
            (result as UrlValidator.Result.Valid).url
        )
    }

    @Test
    fun `http url passes for lan addons`() {
        val result = UrlValidator.validate("http://192.168.1.20:8080/manifest.json")
        assertTrue(result is UrlValidator.Result.Valid)
    }

    @Test
    fun `scheme is added when missing`() {
        val result = UrlValidator.validate("example.com/manifest.json")
        assertTrue(result is UrlValidator.Result.Valid)
        assertEquals(
            "https://example.com/manifest.json",
            (result as UrlValidator.Result.Valid).url
        )
    }

    @Test
    fun `uppercase host is normalized`() {
        val result = UrlValidator.validate("https://EXAMPLE.com/manifest.json")
        assertTrue(result is UrlValidator.Result.Valid)
        assertEquals(
            "https://example.com/manifest.json",
            (result as UrlValidator.Result.Valid).url
        )
    }

    @Test
    fun `empty input is rejected`() {
        assertTrue(UrlValidator.validate("") is UrlValidator.Result.Invalid)
        assertTrue(UrlValidator.validate("   ") is UrlValidator.Result.Invalid)
    }

    @Test
    fun `spaces are rejected`() {
        assertTrue(UrlValidator.validate("https://exa mple.com") is UrlValidator.Result.Invalid)
    }

    @Test
    fun `dangerous schemes are rejected`() {
        assertTrue(UrlValidator.validate("ftp://example.com/x") is UrlValidator.Result.Invalid)
        assertTrue(
            UrlValidator.validate("javascript:alert(1)") is UrlValidator.Result.Invalid
        )
        assertTrue(UrlValidator.validate("file:///etc/passwd") is UrlValidator.Result.Invalid)
    }

    @Test
    fun `userinfo credentials are rejected`() {
        val result = UrlValidator.validate("https://user:pass@example.com/manifest.json")
        assertTrue(result is UrlValidator.Result.Invalid)
    }

    @Test
    fun `missing host is rejected`() {
        assertTrue(UrlValidator.validate("https://") is UrlValidator.Result.Invalid)
    }

    @Test
    fun `path traversal is rejected`() {
        assertTrue(
            UrlValidator.validate("https://example.com/../secret") is UrlValidator.Result.Invalid
        )
    }

    @Test
    fun `base normalization strips manifest and slashes`() {
        assertEquals(
            "https://example.com",
            HttpAddonApi.normalizeBase("https://example.com/manifest.json")
        )
        assertEquals(
            "https://example.com",
            HttpAddonApi.normalizeBase("https://example.com/")
        )
        assertEquals(
            "http://10.0.0.5:7000",
            HttpAddonApi.normalizeBase("http://10.0.0.5:7000")
        )
    }

    @Test
    fun `catalog url builds extras per protocol`() {
        assertEquals(
            "https://example.com/catalog/movie/top.json",
            HttpAddonApi.catalogUrl("https://example.com", "movie", "top")
        )
        assertEquals(
            "https://example.com/catalog/movie/top/search=big%20buck.json",
            HttpAddonApi.catalogUrl("https://example.com", "movie", "top", search = "big buck")
        )
        assertEquals(
            "https://example.com/catalog/series/all/genre=Action&skip=100.json",
            HttpAddonApi.catalogUrl(
                "https://example.com", "series", "all",
                genre = "Action", skip = 100
            )
        )
    }

    @Test
    fun `stream url keeps colon in video ids`() {
        assertEquals(
            "https://example.com/stream/series/tt0108778:1:2.json",
            HttpAddonApi.streamUrl("https://example.com", "series", "tt0108778:1:2")
        )
    }
}
