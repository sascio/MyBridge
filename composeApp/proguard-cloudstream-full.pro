# CloudStream compatibility rules — Android FULL (sideload) distribution only.
#
# Full builds can load CloudStream `.cs3` packages at runtime. Those packages are
# compiled against the CloudStream runtime and common libraries and resolve them
# by their ORIGINAL JVM names via reflection/class loading, which R8 cannot see.
# Without these rules a minified release build loads a plugin and then fails with
# NoClassDefFoundError/NoSuchMethodError at source-resolution time.
#
# These rules are applied only when building the full distribution; the Play
# Store build does not ship the CloudStream runtime at all.

# R8's optimization passes become prohibitively slow across the CloudStream
# runtime dependency graph. Shrinking and obfuscation stay enabled.
-dontoptimize

# The CloudStream runtime ABI that loaded plugins link directly against.
-keep class com.lagradost.** { *; }
-keep interface com.lagradost.** { *; }
-dontwarn com.lagradost.**

# Host compatibility shims resolved by name from plugin bytecode.
-keep class com.lagradost.cloudstream3.** { *; }

# CloudStream 4.8.0's Android UI ABI. These libraries are reached from
# dynamically loaded plugin bytecode, so R8 cannot infer their usage from the
# host's static call graph. Keep the standard runtime families that are
# packaged in the full variant; do not keep unrelated application classes.
-keep class androidx.activity.** { *; }
-keep interface androidx.activity.** { *; }
-keep class androidx.core.** { *; }
-keep interface androidx.core.** { *; }
-keep class androidx.fragment.** { *; }
-keep interface androidx.fragment.** { *; }
-keep class androidx.lifecycle.** { *; }
-keep interface androidx.lifecycle.** { *; }
-keep class androidx.navigation.** { *; }
-keep interface androidx.navigation.** { *; }
-keep class androidx.preference.** { *; }
-keep interface androidx.preference.** { *; }
-keep class androidx.constraintlayout.** { *; }
-keep interface androidx.constraintlayout.** { *; }
-keep class com.google.android.material.** { *; }
-keep interface com.google.android.material.** { *; }
-dontwarn androidx.activity.**
-dontwarn androidx.core.**
-dontwarn androidx.fragment.**
-dontwarn androidx.lifecycle.**
-dontwarn androidx.navigation.**
-dontwarn androidx.preference.**
-dontwarn androidx.constraintlayout.**
-dontwarn com.google.android.material.**

# The Activity is passed across the dynamically loaded CloudStream boundary.
# Keep the host entry point and launcher subclasses named and structurally
# intact. This does not make an incompatible Activity compatible; it prevents
# R8 from obscuring which concrete class was handed to a provider while the
# runtime verifies the actual AppCompatActivity hierarchy.
-keep class com.nuvio.app.MainActivity { *; }
-keep class com.nuvio.app.launcher.** extends com.nuvio.app.MainActivity { *; }

# Some CloudStream providers are compiled against fuzzywuzzy and resolve this
# package by its original JVM name from dynamically loaded .cs3 dex files.
-keep class me.xdrop.fuzzywuzzy.** { *; }
-dontwarn me.xdrop.fuzzywuzzy.**

# Dynamic CloudStream providers can link directly against kotlinx.serialization
# ABI classes, so their JVM names must remain available in full release builds.
-keep class kotlinx.serialization.** { *; }
-keep interface kotlinx.serialization.** { *; }
-dontwarn kotlinx.serialization.**

# Several providers also call CloudStream cryptography helpers directly.
-keep class dev.whyoleg.cryptography.** { *; }
-keep interface dev.whyoleg.cryptography.** { *; }
-dontwarn dev.whyoleg.cryptography.**

# The Kotlin runtime, coroutines and OkHttp.
#
# Measured, not guessed: the DEX type tables of 50 `.cs3` packages published by
# a real CloudStream repository were inspected. Every single one of the 50
# references `kotlin.*`, `kotlinx.coroutines.*` and `okhttp3.*` by their
# original JVM names — a provider is a suspend function that makes HTTP calls,
# so it cannot not reference them. R8 renames those packages in the app, and a
# dynamically loaded plugin then dies on its first call with
# NoClassDefFoundError/NoSuchMethodError. This is invisible in debug builds,
# which are not minified, which is why it could ship.
-keep class kotlin.** { *; }
-keep interface kotlin.** { *; }
-dontwarn kotlin.**
-keep class kotlinx.coroutines.** { *; }
-keep interface kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-keep class okio.** { *; }
-dontwarn okio.**

# Same measurement: kotlinx-datetime and androidx.annotation are exported to
# plugins from composeApp's dependency list precisely so they resolve at
# runtime, which only holds if their names survive minification.
-keep class kotlinx.datetime.** { *; }
-dontwarn kotlinx.datetime.**
-keep class androidx.annotation.** { *; }

# Referenced by every sampled plugin (Android UI entry points used by the
# providers that expose settings), and by subsets of them for JSON/HTML work.
# Keeping a package that this build does not ship is a harmless no-op, so these
# are listed for completeness of the plugin-facing ABI rather than trimmed to
# today's dependency graph.
-keep class androidx.fragment.app.** { *; }
-dontwarn androidx.fragment.app.**
-keep class androidx.appcompat.app.** { *; }
-dontwarn androidx.appcompat.app.**
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-keep class com.fleeksoft.ksoup.** { *; }
-dontwarn com.fleeksoft.ksoup.**

# HTML/HTTP/JS libraries that providers reference by original name.
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**
-keep class com.lagradost.nicehttp.** { *; }
-dontwarn com.lagradost.nicehttp.**
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**
