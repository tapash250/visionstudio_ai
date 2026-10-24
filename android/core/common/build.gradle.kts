plugins {
    alias(libs.plugins.visionstudio.android.library)
    alias(libs.plugins.visionstudio.android.hilt)
}

android {
    namespace = "com.visionstudio.core.common"
}

dependencies {
    api(projects.core.model)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // Export: EXIF metadata and FileProvider-backed sharing.
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.exifinterface)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
