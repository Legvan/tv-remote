# Ktor — keep all server internals
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

# SLF4J — Ktor references it for logging; the Android runtime has no StaticLoggerBinder
-dontwarn org.slf4j.**

# AdbLib — keep ADB client classes
-keep class com.cgutman.adblib.** { *; }
-dontwarn com.cgutman.adblib.**

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keep,includedescriptorclasses class com.porter.tvremote.**$$serializer { *; }
-keepclassmembers class com.porter.tvremote.** {
    *** Companion;
}
-keepclasseswithmembers class com.porter.tvremote.** {
    kotlinx.serialization.KSerializer serializer(...);
}
