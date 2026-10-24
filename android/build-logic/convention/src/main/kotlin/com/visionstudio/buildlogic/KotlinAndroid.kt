package com.visionstudio.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

private val PROJECT_JAVA_VERSION = JavaVersion.VERSION_17
private val PROJECT_JVM_TARGET = JvmTarget.JVM_17

/** Shared Android + Kotlin configuration for both application and library modules. */
internal fun Project.configureKotlinAndroid(commonExtension: CommonExtension<*, *, *, *, *, *>) {
    commonExtension.apply {
        compileSdk = libs.intVersion("androidCompileSdk")

        defaultConfig {
            minSdk = libs.intVersion("androidMinSdk")
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            vectorDrawables.useSupportLibrary = true
        }

        compileOptions {
            sourceCompatibility = PROJECT_JAVA_VERSION
            targetCompatibility = PROJECT_JAVA_VERSION
        }

        testOptions {
            unitTests.isReturnDefaultValues = true
            unitTests.isIncludeAndroidResources = true
            // Robolectric's ParcelFileDescriptor shadow closes descriptors by reflecting into
            // java.io.FileDescriptor, which JDK 17 seals. Open it for the unit-test JVM only.
            unitTests.all { test -> test.jvmArgs("--add-opens=java.base/java.io=ALL-UNNAMED") }
        }

        packaging {
            resources {
                excludes += setOf(
                    "/META-INF/{AL2.0,LGPL2.1}",
                    "/META-INF/INDEX.LIST",
                    "/META-INF/io.netty.versions.properties",
                )
            }
        }
    }

    extensions.configure<KotlinAndroidProjectExtension> {
        compilerOptions {
            jvmTarget.set(PROJECT_JVM_TARGET)
        }
    }

    addSharedTestDependencies()
}

/** Configuration for pure-Kotlin (non-Android) modules such as :core:model. */
internal fun Project.configureKotlinJvm() {
    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = PROJECT_JAVA_VERSION
        targetCompatibility = PROJECT_JAVA_VERSION
    }

    extensions.configure<KotlinJvmProjectExtension> {
        compilerOptions {
            jvmTarget.set(PROJECT_JVM_TARGET)
        }
    }

    addSharedTestDependencies()
}

private fun Project.addSharedTestDependencies() {
    dependencies {
        add("testImplementation", libs.library("junit4"))
        add("testImplementation", libs.library("kotlinx-coroutines-test"))
        add("testImplementation", libs.library("turbine"))
    }
}
