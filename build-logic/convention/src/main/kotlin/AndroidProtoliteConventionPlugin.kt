import com.estatia.realestate.apps.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.exclude

/**
 * Protobuf runtime compatibility policy for the Android application.
 *
 * Firebase's generated lite messages can run against the full protobuf-java
 * runtime, which is also required by OpenTelemetry's generated OTLP messages.
 * Keep one protobuf runtime on the Android runtime classpath to avoid duplicate
 * com.google.protobuf classes.
 */
class AndroidProtoliteConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            configurations.all {
                // Remove legacy/alternate runtimes so Firebase and OTLP resolve
                // against one compatible protobuf implementation.
                exclude(group = "com.google.protobuf", module = "protobuf-lite")
                exclude(group = "com.google.protobuf", module = "protobuf-javalite")
                exclude(group = "com.google.firebase", module = "protolite-well-known-types")
                resolutionStrategy {
                    force(libs.findLibrary("protobufJava").get())
                }
            }
        }
    }
}
