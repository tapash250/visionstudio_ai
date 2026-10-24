import com.visionstudio.buildlogic.library
import com.visionstudio.buildlogic.libs
import com.visionstudio.buildlogic.pluginId
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

class AndroidHiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply(libs.pluginId("ksp"))
                apply(libs.pluginId("hilt"))
            }

            dependencies {
                add("implementation", libs.library("hilt-android"))
                add("ksp", libs.library("hilt-compiler"))
                add("testImplementation", libs.library("hilt-android-testing"))
                add("kspTest", libs.library("hilt-compiler"))
            }
        }
    }
}
