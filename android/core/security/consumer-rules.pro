# Tink (pulled in by androidx.security:security-crypto) references compile-only annotation
# artifacts that are absent at runtime.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn com.google.api.client.http.**
-dontwarn org.joda.time.**
