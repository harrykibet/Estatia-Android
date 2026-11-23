package com.estatia.realestate.apps.graph

import org.gradle.api.Plugin
import org.gradle.api.Project
import java.io.File

class ModuleGraphPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        if (target != target.rootProject) return

        val generateModuleGraphs = target.tasks.register("generateModuleGraphs", GenerateModuleGraphsTask::class.java) {
            group = "reporting"
            description = "Generates module dependency graphs from the resolved Gradle configuration graph."

            dotBinary.set(
                target.providers.gradleProperty("estatia.graphviz.dot")
                    .orElse(target.providers.environmentVariable("GRAPHVIZ_DOT"))
                    .orElse(target.providers.provider {
                        listOf(
                            "C:/Program Files/Graphviz/bin/dot.exe",
                            "/opt/homebrew/bin/dot", "/usr/local/bin/dot", "/usr/bin/dot"
                        ).find { File(it).exists() } ?: "dot"
                    })
            )
            outputDir.set(target.layout.projectDirectory.dir("docs/images/graph"))
        }

        val calculateImpact = target.tasks.register("calculateImpact") {
            group = "verification"
            description = "Calculates the impact of changes on the codebase."
        }

        // Defer input collection until all projects are evaluated to capture full graph
        target.gradle.projectsEvaluated {
            val allSubprojects = target.subprojects
            val modules = allSubprojects.map { it.path }.toSet()
            val edges = ModuleGraphExtractor.extractEdges(allSubprojects)
            val rootDir = target.projectDir // Capture here during configuration
            
            generateModuleGraphs.configure {
                this.modules.set(modules)
                this.edges.set(edges.map { "${it.from}|${it.to}|${it.type}" })
            }
            
            calculateImpact.configure {
                doLast {
                    // PR and main-branch workflows pass the exact comparison range.
                    // Fall back to origin/main for existing callers that do not pass overrides.
                    val impactBase = System.getenv("ESTATIA_IMPACT_BASE")
                        ?.takeIf { it.isNotBlank() } ?: "origin/main"
                    val impactHead = System.getenv("ESTATIA_IMPACT_HEAD")
                        ?.takeIf { it.isNotBlank() } ?: "HEAD"
                    val diffReference = if (impactBase == "origin/main") {
                        impactBase
                    } else {
                        "$impactBase...$impactHead"
                    }
                    val diffOutput = try {
                        ProcessBuilder("git", "diff", "--name-only", diffReference)
                            .start().inputStream.bufferedReader().readText().lines().filter { it.isNotBlank() }
                    } catch (error: Exception) {
                        logger.warn("Could not calculate impacted paths from '$diffReference': ${error.message}")
                        emptyList<String>()
                    }

                    if (diffOutput.isEmpty()) {
                        println("IMPACT_TASKS=test lint")
                        return@doLast
                    }

                    val compileOnly = System.getenv("ESTATIA_COMPILE_ONLY")
                        ?.equals("true", ignoreCase = true) == true
                    if (compileOnly) {
                        logger.lifecycle("CI compile-only fast path enabled by the maintainer's ci:compile-only label.")
                    }

                    val fullImpact = ModuleGraphExtractor.findImpactedModules(diffOutput, modules, edges, rootDir)
                    val tasks = fullImpact.sorted().flatMap { modulePath ->
                        val module = target.findProject(modulePath)
                        if (module == null) {
                            println("INFO: Skipping $modulePath because the Gradle project is not present.")
                            emptyList()
                        } else {
                            val pluginIds = listOf(
                                "com.android.application",
                                "com.android.library",
                                "com.android.test",
                                "org.jetbrains.kotlin.jvm",
                                "java",
                                "java-library"
                            ).filter { module.plugins.hasPlugin(it) }.toSet()

                            val selectedTasks = ImpactVerificationTaskSelector.select(
                                projectPath = module.path,
                                pluginIds = pluginIds,
                                registeredTaskNames = module.tasks.names,
                                changedFiles = diffOutput,
                                compileOnly = compileOnly
                            )
                            if (selectedTasks.isEmpty()) {
                                println(
                                    "INFO: Skipping ${module.path}; no supported impacted verification tasks " +
                                        "are registered for its plugins: ${pluginIds.ifEmpty { setOf("none") }}."
                                )
                            }
                            selectedTasks
                        }
                    }.distinct()

                    if (tasks.isEmpty()) {
                        println("INFO: No module-specific verification tasks were selected; using full test/lint task selectors.")
                        println("IMPACT_TASKS=test lint")
                    } else {
                        println("IMPACT_TASKS=" + tasks.joinToString(" "))
                    }
                }
            }
        }
    }
}
