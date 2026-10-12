import com.android.build.api.dsl.LibraryExtension
import com.visionstudio.buildlogic.configureAndroidCompose
import com.visionstudio.buildlogic.libs
import com.visionstudio.buildlogic.pluginId
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidLibraryComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply(libs.pluginId("android-library"))
            pluginManager.apply(libs.pluginId("compose-compiler"))
            extensions.configure<LibraryExtension> {
                configureAndroidCompose(this)
            }
        }
    }
}
