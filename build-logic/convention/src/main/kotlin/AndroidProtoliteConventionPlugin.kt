import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.exclude

/**
 * Keeps obsolete pre-Lite protobuf artifacts off Android runtime classpaths.
 *
 * Firebase's generated Lite messages require their matching
 * protolite-well-known-types classes. Do not redirect protobuf-javalite to
 * protobuf-java or exclude Firebase's Lite well-known types.
 */
class AndroidProtoliteConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            configurations.configureEach {
                exclude(group = "com.google.protobuf", module = "protobuf-lite")
            }
        }
    }
}
