plugins {
    alias(libs.plugins.visionstudio.android.library)
    alias(libs.plugins.visionstudio.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.visionstudio.core.network"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
}

dependencies {
    api(projects.core.common)
    api(projects.core.model)
    api(projects.core.mcp)
    implementation(projects.core.security)

    api(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
}
