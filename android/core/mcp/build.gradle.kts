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
    implementation(libs.ktor.client.cio)

    testImplementation(libs.ktor.client.mock)
}
