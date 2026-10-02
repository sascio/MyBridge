package com.nuvio.app.core.build

import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Release identity guards for StreamBridge.
 *
 * StreamBridge ships NuvioMobile's core under its own identity: its own version,
 * its own application/package identifiers and its own visible name. These are
 * easy to regress silently during an upstream synchronisation, because every
 * value involved lives in a different file (Gradle properties, Xcode
 * xcconfig/pbxproj, Compose resources). Everything below is a static check: it
 * fails the normal `:composeApp:check` run instead of letting a Nuvio version or
 * brand leak into a StreamBridge release.
 */
class StreamBridgeReleaseIdentityTest {

    private val repositoryRoot: File by lazy {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile &&
                File(dir, "streambridge.version.properties").isFile
            ) {
                return@lazy dir
            }
            dir = dir.parentFile
        }
        error("Could not locate the StreamBridge repository root from ${File(".").absolutePath}")
    }

    private fun readProperties(relativePath: String): Properties {
        val file = File(repositoryRoot, relativePath)
        assertTrue(file.isFile, "$relativePath is missing from the repository.")
        return Properties().apply { file.inputStream().use { load(it) } }
    }

    private fun xcconfigValue(relativePath: String, key: String): String? =
        File(repositoryRoot, relativePath).readLines()
            .asSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("//") && it.contains('=') }
            .map { line ->
                val separator = line.indexOf('=')
                line.substring(0, separator).trim() to line.substring(separator + 1).trim()
            }
            .firstOrNull { (entryKey, _) -> entryKey == key }
            ?.second

    @Test
    fun versionMetadataAgreesAcrossEverySourceOfTruth() {
        val properties = readProperties("streambridge.version.properties")
        val versionName = properties.getProperty("STREAMBRIDGE_VERSION_NAME")?.trim()
        val versionCode = properties.getProperty("STREAMBRIDGE_VERSION_CODE")?.trim()?.toIntOrNull()

        assertFalse(versionName.isNullOrBlank(), "STREAMBRIDGE_VERSION_NAME must be set.")
        assertTrue((versionCode ?: 0) > 0, "STREAMBRIDGE_VERSION_CODE must be a positive integer.")

        // The Gradle build turns these two into the Android/desktop version and the
        // generated AppVersionConfig the app shows and the updater compares against.
        assertEquals(versionName, AppVersionConfig.VERSION_NAME)
        assertEquals(versionCode, AppVersionConfig.VERSION_CODE)

        // Xcode reads its own copy for the iOS bundle.
        val versionXcconfig = "iosApp/Configuration/Version.xcconfig"
        assertEquals(versionName, xcconfigValue(versionXcconfig, "MARKETING_VERSION"))
        assertEquals(
            versionCode.toString(),
            xcconfigValue(versionXcconfig, "CURRENT_PROJECT_VERSION"),
        )

        // The upstream pin recorded for the app must be the one the build was told
        // about, otherwise the in-app attribution is wrong.
        assertEquals(
            properties.getProperty("NUVIO_UPSTREAM_RELEASE")?.trim(),
            AppVersionConfig.NUVIO_UPSTREAM_RELEASE,
        )
        assertEquals(
            properties.getProperty("NUVIO_UPSTREAM_COMMIT")?.trim(),
            AppVersionConfig.NUVIO_UPSTREAM_COMMIT,
        )
    }

    @Test
    fun streamBridgeVersionIsNotNuviosUpstreamVersion() {
        val properties = readProperties("streambridge.version.properties")
        val versionName = properties.getProperty("STREAMBRIDGE_VERSION_NAME")!!.trim()
        val upstreamRelease = properties.getProperty("NUVIO_UPSTREAM_RELEASE")!!.trim()
        val upstreamVersion = upstreamRelease.removeSuffix("-beta")

        assertFalse(
            versionName == upstreamVersion || versionName.startsWith("$upstreamVersion."),
            "StreamBridge must not ship NuvioMobile's version ($upstreamRelease) as its own.",
        )
        assertTrue(
            versionName.startsWith("0.1."),
            "StreamBridge versions follow the 0.1.x line; found $versionName.",
        )
    }

    @Test
    fun visibleApplicationNameIsStreamBridgeInEveryAndroidResourceSet() {
        val androidNameResources = listOf(
            "composeApp/src/androidMain/res/values/strings.xml",
            "composeApp/src/androidMain/res/values-es/strings.xml",
            "composeApp/src/androidMain/res/values-bn/strings.xml",
            "androidApp/src/debug/res/values/strings.xml",
        )
        androidNameResources.forEach { path ->
            val contents = File(repositoryRoot, path).readText()
            val declared = Regex("<string name=\"app_name\">([^<]*)</string>")
                .findAll(contents)
                .map { it.groupValues[1] }
                .toList()
            assertTrue(declared.isNotEmpty(), "$path does not declare app_name.")
            declared.forEach { value ->
                assertTrue(
                    value.startsWith("StreamBridge"),
                    "$path declares app_name as \"$value\" instead of StreamBridge.",
                )
            }
        }
    }

    @Test
    fun brandNameStringIsStreamBridgeInEveryLocaleThatDefinesIt() {
        val localeDirectories = File(repositoryRoot, "composeApp/src/commonMain/composeResources")
            .listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("values") }
            .orEmpty()
        assertTrue(localeDirectories.isNotEmpty(), "No Compose resource locale directories found.")

        var checked = 0
        localeDirectories.forEach { directory ->
            val strings = File(directory, "strings.xml")
            if (!strings.isFile) return@forEach
            val brands = Regex("<string name=\"app_brand_name\">([^<]*)</string>")
                .findAll(strings.readText())
                .map { it.groupValues[1] }
                .toList()
            brands.forEach { brand ->
                checked++
                assertEquals(
                    "StreamBridge",
                    brand,
                    "${strings.relativeTo(repositoryRoot)} brands the app as \"$brand\".",
                )
            }
        }
        assertTrue(checked > 0, "No app_brand_name string was found in any locale.")
    }

    @Test
    fun iosIdentityUsesStreamBridgeNameAndBundleIdentifier() {
        val config = "iosApp/Configuration/Config.xcconfig"
        assertEquals("StreamBridge", xcconfigValue(config, "PRODUCT_NAME"))
        assertEquals(
            "com.streambridge.app\$(TEAM_ID)",
            xcconfigValue(config, "PRODUCT_BUNDLE_IDENTIFIER"),
        )

        val pbxproj = File(repositoryRoot, "iosApp/iosApp.xcodeproj/project.pbxproj").readText()
        val bundleIdentifiers = Regex("PRODUCT_BUNDLE_IDENTIFIER = ([^;]+);")
            .findAll(pbxproj)
            .map { it.groupValues[1].trim() }
            .toList()
        assertTrue(bundleIdentifiers.isNotEmpty(), "The Xcode project declares no bundle identifier.")
        bundleIdentifiers.forEach { identifier ->
            assertTrue(
                identifier.startsWith("com.streambridge.app"),
                "The Xcode project still carries a non-StreamBridge bundle identifier: $identifier",
            )
        }
        assertTrue(
            pbxproj.contains("path = StreamBridge.app;"),
            "The Xcode project does not produce StreamBridge.app.",
        )
        assertTrue(
            !pbxproj.contains("8QBDZ766S3"),
            "The Xcode project still carries NuvioMedia's Apple team identifier.",
        )

        val scheme = File(
            repositoryRoot,
            "iosApp/iosApp.xcodeproj/xcshareddata/xcschemes/iosApp.xcscheme",
        ).readText()
        assertTrue(
            scheme.contains("BuildableName = \"StreamBridge.app\""),
            "The shared scheme does not reference StreamBridge.app.",
        )
        assertFalse(
            scheme.contains("Nuvio.app"),
            "The shared scheme still references Nuvio.app.",
        )
    }
}
