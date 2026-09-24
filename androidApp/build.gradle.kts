import java.util.Properties

fun readXcconfigValue(file: File, key: String): String? {
    if (!file.exists()) return null
    return file.readLines()
        .asSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
        .map { line ->
            val separatorIndex = line.indexOf('=')
            line.substring(0, separatorIndex).trim() to line.substring(separatorIndex + 1).trim()
        }
        .firstOrNull { (entryKey, _) -> entryKey == key }
        ?.second
}

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.sentry.android.gradle)
}

val localProps = Properties().apply {
    val propsFile = rootProject.file("local.properties")
    if (propsFile.exists()) propsFile.inputStream().use { load(it) }
}
fun envOrLocalProperty(key: String): String? =
    providers.environmentVariable(key).orNull?.trim()?.takeIf { it.isNotBlank() }
        ?: localProps.getProperty(key)?.trim()?.takeIf { it.isNotBlank() }

fun isTruthyEnvOrLocal(key: String): Boolean =
    when (envOrLocalProperty(key)?.lowercase()) {
        "1", "true", "yes", "y", "on" -> true
        else -> false
    }

fun resolveKeystoreFile(path: String): File? {
    val asFile = File(path)
    val resolved = if (asFile.isAbsolute) asFile else rootProject.file(path)
    return resolved.takeIf { it.isFile }
}

val releaseStoreFilePath = envOrLocalProperty("NUVIO_RELEASE_STORE_FILE")
val releaseStorePassword = envOrLocalProperty("NUVIO_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = envOrLocalProperty("NUVIO_RELEASE_KEY_ALIAS")
val releaseKeyPassword = envOrLocalProperty("NUVIO_RELEASE_KEY_PASSWORD")
val releaseKeystore = releaseStoreFilePath?.let(::resolveKeystoreFile)
val hasCompleteReleaseSigning =
    releaseKeystore != null &&
        !releaseStorePassword.isNullOrBlank() &&
        !releaseKeyAlias.isNullOrBlank() &&
        !releaseKeyPassword.isNullOrBlank()
val requireProductionSigning = isTruthyEnvOrLocal("STREAMBRIDGE_REQUIRE_PRODUCTION_SIGNING")
if (requireProductionSigning && !hasCompleteReleaseSigning) {
    error(
        "Production release requires NUVIO_RELEASE_STORE_FILE (existing keystore), " +
            "NUVIO_RELEASE_STORE_PASSWORD, NUVIO_RELEASE_KEY_ALIAS, and " +
            "NUVIO_RELEASE_KEY_PASSWORD via environment or local.properties. " +
            "Debug-signed APKs are not production artifacts. See docs/SIGNING.md.",
    )
}

val sentryAuthToken = envOrLocalProperty("SENTRY_AUTH_TOKEN")
val sentryOrg = envOrLocalProperty("SENTRY_ORG")
val sentryProject = envOrLocalProperty("SENTRY_PROJECT")
val sentryMappingUploadEnabled = sentryAuthToken != null && sentryOrg != null && sentryProject != null
val appVersionConfigFile = rootProject.file("iosApp/Configuration/Version.xcconfig")
// StreamBridge's own user-facing version (see streambridge.version.properties).
// Keep this ahead of Enhanced's `nuvio.app.versionName` so we never ship Nuvio's
// version number as StreamBridge's.
val streamBridgeProps = Properties().apply {
    val f = rootProject.file("streambridge.version.properties")
    if (f.exists()) f.inputStream().use(::load)
}
val releaseAppVersionName = streamBridgeProps.getProperty("STREAMBRIDGE_VERSION_NAME")?.trim()?.takeIf { it.isNotBlank() }
    ?: providers.gradleProperty("nuvio.app.versionName").orNull
    ?: readXcconfigValue(appVersionConfigFile, "MARKETING_VERSION")
    ?: error("MARKETING_VERSION is missing from ${appVersionConfigFile.path}")
val releaseAppVersionCode = streamBridgeProps.getProperty("STREAMBRIDGE_VERSION_CODE")?.trim()?.toIntOrNull()
    ?: readXcconfigValue(appVersionConfigFile, "CURRENT_PROJECT_VERSION")?.toIntOrNull()
    ?: error("CURRENT_PROJECT_VERSION is missing or invalid in ${appVersionConfigFile.path}")
val requestedTaskNames = gradle.startParameter.taskNames.map { it.substringAfterLast(':') }
val buildsReleaseApks = requestedTaskNames.any {
    it.startsWith("assemble", ignoreCase = true) && it.endsWith("Release", ignoreCase = true)
}

android {
    namespace = "com.nuvio.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    compileSdkMinor = libs.versions.android.compileSdkMinor.get().toInt()

    signingConfigs {
        if (hasCompleteReleaseSigning) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    defaultConfig {
        applicationId = "com.streambridge.app"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = releaseAppVersionCode
        versionName = releaseAppVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("full") {
            dimension = "distribution"
            // Only the sideload distribution can load CloudStream .cs3 packages,
            // so only it needs the keep rules that make dynamically loaded
            // provider bytecode resolve against the embedded runtime.
            proguardFile("../composeApp/proguard-cloudstream-full.pro")
        }
        create("playstore") {
            dimension = "distribution"
        }
    }

    sourceSets.getByName("full") {
        manifest.srcFile("src/full/AndroidManifest.xml")
        jniLibs.directories.add("../composeApp/src/full/jniLibs")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += listOf(
                "lib/*/libc++_shared.so",
                "lib/*/libavcodec.so",
                "lib/*/libavutil.so",
                "lib/*/libswscale.so",
                "lib/*/libswresample.so"
            )
        }
    }

    androidResources {
        noCompress += "cvr"
    }

    splits {
        abi {
            // Same as NuvioMobile: assemble*Release emits one APK per ABI.
            // A universal APK packs libnuvio_engine + Media3 JNI + libmpv +
            // QuickJS for four ABIs and is ~3–4× the download size. The
            // in-app updater selects the GitHub asset whose name contains
            // the device ABI. Signing is independent of this split.
            isEnable = buildsReleaseApks
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = false
        }
    }

    buildTypes {
        getByName("release") {
            val minifyRelease = providers.gradleProperty("releaseMinifyEnabled")
                .map(String::toBooleanStrict)
                .getOrElse(true)
            isMinifyEnabled = minifyRelease
            isShrinkResources = minifyRelease
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "../composeApp/proguard-rules.pro",
            )
            signingConfig = if (hasCompleteReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            ndk {
                debugSymbolLevel = "FULL"
            }
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.applicationId.set("com.streambridge.app")
    }
}

sentry {
    includeProguardMapping.set(true)
    autoUploadProguardMapping.set(sentryMappingUploadEnabled)
    uploadNativeSymbols.set(false)
    autoUploadNativeSymbols.set(false)
    includeNativeSources.set(false)
    includeSourceContext.set(false)
    autoUploadSourceContext.set(false)
    includeDependenciesReport.set(false)
    telemetry.set(false)
    sentryAuthToken?.let(authToken::set)
    sentryOrg?.let(org::set)
    sentryProject?.let(projectName::set)
    ignoredBuildTypes.set(setOf("debug"))
    autoInstallation {
        enabled.set(false)
    }
    tracingInstrumentation {
        enabled.set(false)
    }
}

dependencies {
    implementation(project(":composeApp"))
    implementation(libs.androidx.appcompat)

    // CloudStream compatibility runtime (GPL-3.0), sideload distribution only.
    //
    // This must be declared here, on the application module, in addition to
    // composeApp. A local .aar file dependency of a Kotlin Multiplatform
    // library source set is not reliably exported to the consuming
    // application's runtime classpath, so composeApp compiled against it while
    // the APK shipped without it: every plugin load would then fail on device
    // with NoClassDefFoundError for BasePlugin, even though CI was green.
    //
    // `fullImplementation` keeps it strictly out of the Play Store variant, so
    // the no-DEX boundary is unchanged. Integrity is still enforced by
    // :composeApp:verifyCloudStreamRuntime against the pinned SHA-256.
    "fullImplementation"(files("../composeApp/libs/cloudstream-runtime-api-4.8.0-3496e5f.aar"))
    // Libraries that dynamically loaded plugin bytecode resolves by its
    // original JVM names; unreachable to R8's static analysis.
    "fullImplementation"("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1")
    "fullImplementation"("org.jsoup:jsoup:1.22.1")
    "fullImplementation"("com.github.Blatzar:NiceHttp:0.4.18")
    "fullImplementation"("me.xdrop:fuzzywuzzy:1.4.0")
    "fullImplementation"("org.mozilla:rhino:1.8.1")
    "fullImplementation"("dev.whyoleg.cryptography:cryptography-core:0.6.0")
    "fullImplementation"("dev.whyoleg.cryptography:cryptography-provider-optimal:0.6.0")

    implementation(libs.compose.runtime)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    debugImplementation(libs.compose.uiTooling)
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation(libs.androidx.testExt.junit)
    androidTestImplementation(libs.androidx.activity.compose)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:${libs.versions.composeMultiplatform.get()}")
    debugImplementation("androidx.compose.ui:ui-test-manifest:${libs.versions.composeMultiplatform.get()}")
}
