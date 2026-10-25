plugins {
    alias(libs.plugins.visionstudio.android.library)
    alias(libs.plugins.visionstudio.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.visionstudio.core.mcp"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
}

dependencies {
    api(projects.core.common)
    api(libs.kotlinx.serialization.json)
    api(libs.ktor.client.core)
    // OkHttp engine: TLS through the platform's SSLSocket, so Android's Network Security Config
    // and hostname-aware certificate validation apply. Do not switch to CIO (see McpClientFactory).
    implementation(libs.ktor.client.okhttp)

    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
    // Only to prove, in a test, why CIO fails on Android (it skips hostname-aware validation).
    testImplementation(libs.ktor.client.cio)
}
