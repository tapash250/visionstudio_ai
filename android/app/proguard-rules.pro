# Keep source/line info for readable crash traces; obfuscate file names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---------------------------------------------------------------------------
# kotlinx.serialization
# Every @Serializable model in the app and its feature/core modules is encoded and decoded
# through a generated `$$serializer`, located at runtime through `Companion.serializer()`.
# ---------------------------------------------------------------------------
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# Companion accessors and singleton serializers for @Serializable classes and objects.
-keepclassmembers @kotlinx.serialization.Serializable class com.visionstudio.** {
    *** Companion;
    static ** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# Generated serializers keep their descriptors, child serializers and constructors.
-keepclasseswithmembers class com.visionstudio.**$$serializer { *; }

# Companion objects of @Serializable classes expose the serializer() lookup.
-keepclassmembers class com.visionstudio.**$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}

# Enums used in @Serializable models are looked up by name through their generated serializer.
-keepclassmembers @kotlinx.serialization.Serializable enum com.visionstudio.** {
    <fields>;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ---------------------------------------------------------------------------
# Ktor CIO engine (MCP client)
# The engine is discovered through ServiceLoader, which R8 cannot trace statically.
# ---------------------------------------------------------------------------
-keep class io.ktor.client.engine.cio.CIOEngineContainer { *; }
-keep class * implements io.ktor.client.HttpClientEngineContainer { *; }
-keepclassmembers class io.ktor.** { volatile <fields>; }

# Ktor references JVM-only logging and management APIs that do not exist on Android.
-dontwarn org.slf4j.**
-dontwarn java.lang.management.**
-dontwarn io.ktor.utils.io.jvm.nio.**
-dontwarn io.ktor.util.debug.**

# ---------------------------------------------------------------------------
# OkHttp / Tink optional dependencies (their own R8 rules ship with the artifacts)
# ---------------------------------------------------------------------------
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
