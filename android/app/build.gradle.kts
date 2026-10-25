plugins {
    alias(libs.plugins.visionstudio.android.application)
    alias(libs.plugins.visionstudio.android.application.compose)
    alias(libs.plugins.visionstudio.android.hilt)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "com.visionstudio.app"

    defaultConfig {
        applicationId = "com.visionstudio.ai"
        versionCode = 1
        versionName = "0.1.0"
    }

    // A release key from the environment, so CI signs every build with the same certificate and
    // each APK installs over the previous one (`adb install -r`) with app data intact. Set
    // VISIONSTUDIO_KEYSTORE_FILE plus the three VISIONSTUDIO_* passwords/alias below; CI decodes
    // them from repository secrets. Without them, release builds use this machine's debug key.
    val releaseSigning = providers.environmentVariable("VISIONSTUDIO_KEYSTORE_FILE").orNull?.let { path ->
        fun required(name: String): String = providers.environmentVariable(name).orNull
            ?: error("VISIONSTUDIO_KEYSTORE_FILE is set but $name is not")
        signingConfigs.create("release") {
            storeFile = file(path)
            storePassword = required("VISIONSTUDIO_KEYSTORE_PASSWORD")
            keyAlias = required("VISIONSTUDIO_KEY_ALIAS")
            keyPassword = required("VISIONSTUDIO_KEY_PASSWORD")
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = releaseSigning ?: signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.model)
    implementation(projects.core.designsystem)
    implementation(projects.core.security)
    implementation(projects.core.network)
    implementation(projects.core.database)
    implementation(projects.core.data)
    implementation(projects.feature.settings)
    implementation(projects.feature.t2i)
    implementation(projects.feature.imageedit)
    implementation(projects.feature.video)
    implementation(projects.feature.gallery)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    baselineProfile(projects.baselineprofile)
}

dependencies {
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
