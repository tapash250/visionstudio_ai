plugins {
    alias(libs.plugins.visionstudio.android.library)
    alias(libs.plugins.visionstudio.android.library.compose)
    alias(libs.plugins.visionstudio.android.hilt)
}

android {
    namespace = "com.visionstudio.feature.video"
}

dependencies {
    implementation(projects.core.designsystem)
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.core.data)
    implementation(projects.core.security)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.core.ktx)

    // Background generation
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // Playback
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.common)

    // The worker tests drive the real Room DAO; production code only sees :core:data.
    testImplementation(projects.core.database)
    testImplementation(projects.core.network)
    testImplementation(libs.room.runtime)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.work.testing)
}
