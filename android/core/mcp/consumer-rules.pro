# Ktor CIO resolves engine containers and coroutine debug hooks reflectively.
-keep class io.ktor.client.engine.cio.CIOEngineContainer { *; }
-keepclassmembers class io.ktor.** { volatile <fields>; }
-dontwarn org.slf4j.**
-dontwarn java.lang.management.**
-dontwarn io.ktor.utils.io.jvm.nio.**

# kotlinx.serialization: MCP wire models.
-keepclassmembers @kotlinx.serialization.Serializable class com.visionstudio.core.mcp.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class com.visionstudio.core.mcp.**$$serializer { *; }
