plugins {
    alias(libs.plugins.visionstudio.android.library)
    alias(libs.plugins.visionstudio.android.hilt)
}

android {
    namespace = "com.visionstudio.core.data"
}

dependencies {
    api(projects.core.model)
    api(projects.core.common)
    api(projects.core.network)
    implementation(projects.core.database)
    implementation(projects.core.security)
    implementation(libs.kotlinx.coroutines.android)
}
