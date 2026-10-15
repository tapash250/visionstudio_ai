plugins {
    alias(libs.plugins.visionstudio.android.library)
    alias(libs.plugins.visionstudio.android.library.compose)
}

android {
    namespace = "com.visionstudio.core.player"
}

dependencies {
    implementation(projects.core.designsystem)

    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.core.ktx)

    api(libs.androidx.media3.exoplayer)
    api(libs.androidx.media3.ui)
    api(libs.androidx.media3.common)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
