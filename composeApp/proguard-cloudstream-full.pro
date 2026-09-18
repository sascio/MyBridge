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

# HTML/HTTP/JS libraries that providers reference by original name.
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**
-keep class com.lagradost.nicehttp.** { *; }
-dontwarn com.lagradost.nicehttp.**
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**
