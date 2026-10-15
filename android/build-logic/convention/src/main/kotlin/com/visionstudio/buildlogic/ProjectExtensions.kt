package com.visionstudio.buildlogic

import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.getByType

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.library(alias: String): Provider<MinimalExternalModuleDependency> =
    findLibrary(alias).orElseThrow {
        IllegalStateException("Library alias '$alias' is missing from gradle/libs.versions.toml")
    }

internal fun VersionCatalog.pluginId(alias: String): String =
    findPlugin(alias).orElseThrow {
        IllegalStateException("Plugin alias '$alias' is missing from gradle/libs.versions.toml")
    }.get().pluginId

internal fun VersionCatalog.intVersion(alias: String): Int =
    findVersion(alias).orElseThrow {
        IllegalStateException("Version alias '$alias' is missing from gradle/libs.versions.toml")
    }.requiredVersion.toInt()
