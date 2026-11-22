rootProject.name = "Nuvio"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        // Robolectric's large Android SDK jars are frequently throttled on Maven
        // Central's shared CI IPs. Google's Maven Central mirror hosts the same
        // published artifacts; restrict it to these SDK jars only.
        maven("https://maven-central.storage-download.googleapis.com/maven2") {
            content { includeModule("org.robolectric", "android-all-instrumented") }
        }
        mavenCentral()
        // Required by the CloudStream compatibility runtime (full distribution):
        // NiceHttp is published via JitPack. Restricted to com.github.* so no
        // other dependency can silently resolve from it.
        maven("https://jitpack.io") {
            content {
                includeGroupByRegex("com\\.github\\..*")
            }
        }
    }
}

include(":composeApp")
include(":androidApp")
include(":baselineprofile")
