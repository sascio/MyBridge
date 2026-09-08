# Stream Bridge release rules.

# kotlinx-serialization: keep generated serializers for our models.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.streambridge.app.**$$serializer { *; }
-keepclassmembers class com.streambridge.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.streambridge.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp, Coil, Media3, Room and Compose ship their own consumer rules.
