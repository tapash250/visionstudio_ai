import com.android.build.api.dsl.LibraryExtension
import com.visionstudio.buildlogic.configureKotlinAndroid
import com.visionstudio.buildlogic.intVersion
import com.visionstudio.buildlogic.libs
import com.visionstudio.buildlogic.pluginId
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply(libs.pluginId("android-library"))
                apply(libs.pluginId("kotlin-android"))
            }

            extensions.configure<LibraryExtension> {
                configureKotlinAndroid(this)
                testOptions.targetSdk = libs.intVersion("androidTargetSdk")
                lint.targetSdk = libs.intVersion("androidTargetSdk")
            }
        }
    }
}
