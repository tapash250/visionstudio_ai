import com.visionstudio.buildlogic.configureKotlinJvm
import com.visionstudio.buildlogic.libs
import com.visionstudio.buildlogic.pluginId
import org.gradle.api.Plugin
import org.gradle.api.Project

class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply(libs.pluginId("kotlin-jvm"))
            configureKotlinJvm()
        }
    }
}
