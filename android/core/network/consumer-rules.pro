# OkHttp ships its own R8 rules; these silence optional TLS providers it probes for.
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

-keepclassmembers @kotlinx.serialization.Serializable class com.visionstudio.core.network.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class com.visionstudio.core.network.**$$serializer { *; }
