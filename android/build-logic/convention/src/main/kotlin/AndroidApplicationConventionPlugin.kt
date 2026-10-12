import com.android.build.api.dsl.ApplicationExtension
import com.visionstudio.buildlogic.configureKotlinAndroid
import com.visionstudio.buildlogic.intVersion
import com.visionstudio.buildlogic.libs
import com.visionstudio.buildlogic.pluginId
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply(libs.pluginId("android-application"))
                apply(libs.pluginId("kotlin-android"))
            }

            extensions.configure<ApplicationExtension> {
                configureKotlinAndroid(this)
                defaultConfig.targetSdk = libs.intVersion("androidTargetSdk")
                buildFeatures.buildConfig = true
            }
        }
    }
}
