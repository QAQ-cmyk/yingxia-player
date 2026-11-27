# Google Tink reflection & protobuf keep rules
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

# Kotlinx Serialization (used in sync flavor)
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class cn.yingxia.protocol.**$$serializer { *; }
-keepclassmembers class cn.yingxia.protocol.** {
    *** Companion;
}
-keepclasseswithmembers class cn.yingxia.protocol.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Room generated implementations
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
