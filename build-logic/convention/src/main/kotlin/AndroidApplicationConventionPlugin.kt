import androidx.baselineprofile.gradle.consumer.BaselineProfileConsumerExtension
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.artifact.SingleArtifact
import com.estatia.realestate.apps.release.AuditSymbolObfuscationTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies
import java.io.File

class AndroidApplicationConventionPlugin : Plugin<Project> {
    private fun Project.optionalSecret(name: String): String? =
        providers.gradleProperty(name).orNull
            ?: providers.environmentVariable(name).orNull


    override fun apply(target: Project) = with(target) {

        pluginManager.apply("com.android.application")
        pluginManager.apply("androidx.baselineprofile")

        // Keep Demo and Prod release profiles separate and versioned with source.
        // Startup and full journey rules are generated into each variant's source set.
        extensions.configure<BaselineProfileConsumerExtension>("baselineProfile") {
            mergeIntoMain = false
            saveInSrc = true
        }
        pluginManager.apply("com.estatia.realestate.apps.android.hilt")
        pluginManager.apply("com.estatia.realestate.apps.android.config")
        pluginManager.apply("com.estatia.realestate.apps.android.protolite")
        pluginManager.apply("com.estatia.realestate.apps.android.flavors")
        pluginManager.apply("com.estatia.realestate.apps.android.application.firebase")
        pluginManager.apply("com.estatia.realestate.apps.android.testing")
        pluginManager.apply("com.estatia.realestate.apps.android.compose")
        pluginManager.apply("com.estatia.realestate.apps.android.packaging")
        pluginManager.apply("com.estatia.realestate.apps.android.jacoco")

        // Generate the Android debug keystore only when a debug APK is packaged.
        // Keeping external processes out of project configuration makes Gradle
        // configuration-cache and GitHub dependency-submission validation safe.
        val debugKeystoreFile = rootProject.file("AppKeyStore/debug.keystore")
        val generateDebugKeystore = tasks.register("generateDebugKeystore") {
            outputs.file(debugKeystoreFile)
            doLast {
                if (!debugKeystoreFile.exists()) {
                    debugKeystoreFile.parentFile?.mkdirs()
                    val keytoolPath = File(System.getProperty("java.home"), "bin/keytool").absolutePath
                    val process = ProcessBuilder(
                        keytoolPath,
                        "-genkeypair",
                        "-keystore", debugKeystoreFile.absolutePath,
                        "-alias", "androiddebugkey",
                        "-keyalg", "RSA",
                        "-keysize", "2048",
                        "-validity", "10000",
                        "-keypass", "android",
                        "-storepass", "android",
                        "-dname", "CN=Android Debug,O=Android,C=US",
                        "-storetype", "JKS"
                    ).redirectErrorStream(true).start()
                    val output = process.inputStream.bufferedReader().readText()
                    val exitCode = process.waitFor()
                    if (exitCode != 0) {
                        error("Failed to generate debug keystore at ${debugKeystoreFile.absolutePath}:\\n$output")
                    }
                }
            }
        }
        tasks.configureEach {
            val packagesDebugVariant =
                name.startsWith("package", ignoreCase = true) &&
                    name.contains("Debug", ignoreCase = true)
            // The Baseline Profile plugin creates non-minified release variants that
            // can use the debug signing config. Their validation tasks run before
            // package tasks, so prepare the debug keystore at validation time too.
            val validatesDebugSignedVariant =
                name.startsWith("validateSigning", ignoreCase = true) &&
                    (name.contains("Debug", ignoreCase = true) ||
                        name.contains("Benchmark", ignoreCase = true) ||
                        name.contains("NonMinifiedRelease", ignoreCase = true))
            if (packagesDebugVariant || validatesDebugSignedVariant) {
                dependsOn(generateDebugKeystore)
            }
        }

        extensions.configure<ApplicationExtension>("android") {

            compileSdk { version = release(37) }

            buildFeatures {
                buildConfig = true
            }

            defaultConfig {
                applicationId = "com.estatia.realestate.apps"
                minSdk = 28
                targetSdk = 37
                versionCode = 1
                versionName = "1.0"

                // Required for AppAuth (AWS Cognito / OIDC)
                manifestPlaceholders["appAuthRedirectScheme"] = "com.estatia.realestate.apps.auth"
                // Required for AWS Amplify Auth
                manifestPlaceholders["amplify_auth_redirect_scheme"] = "com.estatia.realestate.apps.auth"
            }

            signingConfigs {

                // Debug: generate keystore at runtime if absent.
                // Fixed credentials match Android SDK defaults — safe to hardcode,
                // nothing sensitive, debug APKs are never shipped.
                getByName("debug") {
                    storeFile = debugKeystoreFile
                    storePassword = "android"
                    keyAlias = "androiddebugkey"
                    keyPassword = "android"
                }

                // Release: all values must come from CI/CD environment or local
                // gradle.properties (gitignored). Never committed to source control.
                // Config is skipped entirely if secrets are absent (local dev on
                // non-release builds won't need it).
                val releaseStoreFile = rootProject.file("AppKeyStore/keystore.jks")
                val releaseStorePassword = optionalSecret("RELEASE_STORE_PASSWORD")
                val releaseKeyAlias = optionalSecret("RELEASE_KEY_ALIAS")
                val releaseKeyPassword = optionalSecret("RELEASE_KEY_PASSWORD")

                if (releaseStoreFile.exists()
                    && releaseStorePassword != null
                    && releaseKeyAlias != null
                    && releaseKeyPassword != null
                ) {
                    create("release") {
                        storeFile = releaseStoreFile
                        storePassword = releaseStorePassword
                        keyAlias = releaseKeyAlias
                        keyPassword = releaseKeyPassword
                    }
                }
            }

            buildTypes {
                getByName("debug") {
                    signingConfig = signingConfigs.getByName("debug")
                    isMinifyEnabled = false
                }

                getByName("release") {
                    // Falls back to null signingConfig if release secrets absent —
                    // release builds will fail at assemble time, not at sync time.
                    // This allows local development and sync to work without CI secrets.
                    signingConfig = signingConfigs.findByName("release")
                    isMinifyEnabled = true
                }

                create("benchmark") {
                    initWith(buildTypes.getByName("release"))
                    signingConfig = signingConfigs.getByName("debug") // benchmark uses debug sig
                    matchingFallbacks += listOf("release")
                    isDebuggable = false
                }


            }

        }

        extensions.configure<ApplicationAndroidComponentsExtension>("androidComponents") {
            onVariants(selector().withBuildType("release")) { variant ->
                val mappingFile = variant.artifacts.get(SingleArtifact.OBFUSCATION_MAPPING_FILE)
                
                val auditTask = tasks.register("audit${variant.name.replaceFirstChar { it.uppercase() }}ReleaseSymbols", AuditSymbolObfuscationTask::class.java) {
                    group = "verification"
                    description = "Audits release symbols for variant ${variant.name} (LAW-038)."
                    this.mappingFile.set(mappingFile)
                    this.applicationPackage.set("com.estatia.realestate.apps")
                    this.forbiddenPatterns.set(
                        listOf(
                            """\bSecret\w*""",
                            """\bInternalImpl\w*""",
                            """\bSecretStore\w*""",
                            """\bDebugConfig\w*"""
                        )
                    )
                    this.allowlist.set(
                        listOf("ApiKeyValidator", "IApiKeyValidator", "ApiKeyValidatorModule")
                    )
                    this.reportFile.set(layout.buildDirectory.file("reports/architecture/obfuscation/${variant.name}.txt"))
                }
                
                // Wire up to the global root gate if it exists
                rootProject.tasks.matching { it.name == "auditReleaseSymbols" }.configureEach {
                    dependsOn(auditTask)
                }
            }
        }

        dependencies {
            "implementation"(project(":core:architecture-annotations"))
        }
    }
}
