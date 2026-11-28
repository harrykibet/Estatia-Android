package com.estatia.realestate.apps.core.network

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Guards the client/server credential boundary.
 *
 * Mobile production source must never grow a client-callable Secret Manager
 * retrieval abstraction. This intentionally checks endpoint/repository types
 * and legacy resource identifiers, not generic methods such as getSecret that
 * are also legitimately used by the local Android Keystore. Test fixtures are
 * excluded because architecture adversarial fixtures model forbidden designs.
 */
class ClientSecretBoundaryTest {

    @Test
    fun `production client sources contain no Secret Manager retrieval API`() {
        val root = findRepositoryRoot()
        val forbiddenTokens = listOf(
            "SecretApi",
            "ISecretRepository",
            "ISecretRemoteDataSource",
            "SecretRemoteDataSource",
            "DemoSecretRemoteDataSource",
            "SecretRepository",
            "SecretId",
            "ISecureKeyProvider",
            "BuildConfigSecureKeyProvider",
            "StripePaymentAPIKey",
            "INTERGRITY_API_KEY",
            "AI_ML_API_Key",
        )
        val canaryFixtures = root.resolve("core/canary-violations").normalize()
        val violations = mutableListOf<String>()

        Files.walk(root).use { paths ->
            paths
                .filter { Files.isRegularFile(it) }
                .filter { file ->
                    val name = file.fileName.toString()
                    name.endsWith(".kt") || name.endsWith(".java")
                }
                .filter { path ->
                    path.none { component ->
                        component.toString() in setOf(".git", ".gradle", "build", "node_modules")
                    }
                }
                .filter { !it.startsWith(canaryFixtures) }
                .filter(::isProductionSource)
                .forEach { source ->
                    val contents = Files.readString(source)
                    forbiddenTokens
                        .filter { token -> contents.contains(token) }
                        .forEach { token ->
                            violations += "${root.relativize(source)} contains '$token'"
                        }
                }
        }

        assertTrue(
            violations.isEmpty(),
            "Client production source must not expose or depend on server-only Secret Manager retrieval:\n" +
                violations.joinToString("\n"),
        )
    }

    private fun isProductionSource(path: Path): Boolean {
        val parts = path.map { it.toString() }.toList()
        val srcIndex = parts.indexOfLast { it == "src" }
        if (srcIndex < 0) return false

        val sourceSet = parts.getOrNull(srcIndex + 1) ?: return false
        return !sourceSet.contains("test", ignoreCase = true)
    }

    private fun findRepositoryRoot(): Path {
        var current: Path? = Path.of("").toAbsolutePath().normalize()
        while (current != null) {
            if (Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
                return current
            }
            current = current.parent
        }
        error("Could not locate the Android repository root from the test working directory")
    }
}
