plugins {
    alias(libs.plugins.visionstudio.android.library)
    alias(libs.plugins.visionstudio.android.library.compose)
}

android {
    namespace = "com.visionstudio.core.designsystem"
}

dependencies {
    api(libs.androidx.compose.foundation)
    api(libs.androidx.compose.material3)
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.ui.graphics)
    api(libs.androidx.compose.runtime)
    api(libs.androidx.compose.animation)
    api(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.core.ktx)
}
