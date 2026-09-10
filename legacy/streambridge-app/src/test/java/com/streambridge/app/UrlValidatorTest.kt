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
    fun `plain http on a public host is rejected for installs`() {
        val result = UrlValidator.validate("http://public-addon.example.com/manifest.json")
        assertTrue(result is UrlValidator.Result.Invalid)
        assertTrue(
            (result as UrlValidator.Result.Invalid).reason.contains("https")
        )
    }

    @Test
    fun `https on a public host keeps full verification`() {
        val result = UrlValidator.validate("https://v3-cinemeta.strem.io/manifest.json")
        assertTrue(result is UrlValidator.Result.Valid)
        assertEquals(
            "https://v3-cinemeta.strem.io/manifest.json",
            (result as UrlValidator.Result.Valid).url
        )
    }

    @Test
    fun `http localhost and local names are allowed`() {
        assertTrue(UrlValidator.validate("http://127.0.0.1:11470/manifest.json") is UrlValidator.Result.Valid)
        assertTrue(UrlValidator.validate("http://localhost:8080/manifest.json") is UrlValidator.Result.Valid)
        assertTrue(UrlValidator.validate("http://my-nas.local/manifest.json") is UrlValidator.Result.Valid)
    }

    @Test
    fun `more private ranges are recognized as local`() {
        assertTrue(UrlValidator.validate("http://10.0.0.5/manifest.json") is UrlValidator.Result.Valid)
        assertTrue(UrlValidator.validate("http://172.16.4.9/manifest.json") is UrlValidator.Result.Valid)
        assertTrue(UrlValidator.validate("http://[::1]:8080/manifest.json") is UrlValidator.Result.Valid)
        assertTrue(UrlValidator.validate("http://169.254.7.7/manifest.json") is UrlValidator.Result.Valid)
    }

    @Test
    fun `content urls may use public http when explicitly allowed`() {
        val result = UrlValidator.validate(
            "http://cdn.example.com/video.mp4",
            allowPublicHttp = true
        )
        assertTrue(result is UrlValidator.Result.Valid)
        // The install path still refuses the very same URL.
        assertTrue(UrlValidator.validate("http://cdn.example.com/video.mp4") is UrlValidator.Result.Invalid)
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
