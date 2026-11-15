package com.estatia.realestate.apps.release

import org.junit.Assert.assertEquals
import org.junit.Test

class AuditSymbolObfuscationTaskTest {

    private val applicationPackage = "com.estatia.realestate.apps"
    private val forbiddenPatterns = listOf(
        Regex("""\bSecret\w*"""),
        Regex("""\bInternalImpl\w*"""),
        Regex("""\bSecretStore\w*"""),
        Regex("""\bDebugConfig\w*""")
    )
    private val allowlist = setOf(
        "ApiKeyValidator",
        "IApiKeyValidator",
        "ApiKeyValidatorModule"
    )

    private fun audit(vararg mappingLines: String): List<String> =
        findRetainedSensitiveSymbols(
            mappingLines = mappingLines.toList(),
            applicationPackage = applicationPackage,
            forbiddenPatterns = forbiddenPatterns,
            allowlist = allowlist
        )

    @Test
    fun flagsSensitiveApplicationClassWhenItsClassNameIsUnchanged() {
        assertEquals(
            listOf(
                "L1: com.estatia.realestate.apps.security.SecretStore -> " +
                    "com.estatia.realestate.apps.security.SecretStore (matched \\bSecret\\w*)"
            ),
            audit(
                "com.estatia.realestate.apps.security.SecretStore -> " +
                    "com.estatia.realestate.apps.security.SecretStore:"
            )
        )
    }

    @Test
    fun acceptsSensitiveApplicationClassWhenR8RenamesIt() {
        assertEquals(
            emptyList<String>(),
            audit("com.estatia.realestate.apps.security.SecretStore -> a:")
        )
    }

    @Test
    fun flagsSensitiveClassWhenOnlyItsPackageWasRenamed() {
        assertEquals(
            listOf(
                "L1: com.estatia.realestate.apps.security.SecretStore -> " +
                    "a.SecretStore (matched \\bSecret\\w*)"
            ),
            audit("com.estatia.realestate.apps.security.SecretStore -> a.SecretStore:")
        )
    }

    @Test
    fun flagsSensitiveNestedClassNameThatSurvivesRenaming() {
        assertEquals(
            listOf(
                "L1: com.estatia.realestate.apps.security.Container\$SecretStore -> " +
                    "a\$SecretStore (matched \\bSecret\\w*)"
            ),
            audit(
                "com.estatia.realestate.apps.security.Container\$SecretStore -> a\$SecretStore:"
            )
        )
    }

    @Test
    fun ignoresThirdPartyClassesEvenWhenTheirNamesMatchSensitivePatterns() {
        assertEquals(
            emptyList<String>(),
            audit(
                "com.google.crypto.tink.SecretKeyAccess -> " +
                    "com.google.crypto.tink.SecretKeyAccess:",
                "com.vendor.InternalImpl -> com.vendor.InternalImpl:"
            )
        )
    }

    @Test
    fun ignoresIndentedMemberMappingsAndMethodParameterTypes() {
        assertEquals(
            emptyList<String>(),
            audit(
                "com.estatia.realestate.apps.analytics.MetricsModule -> a:",
                "    1:1:void record(com.google.crypto.tink.SecretKeyAccess):161:161 -> a",
                "    com.google.crypto.tink.SecretKeyAccess secretKeyAccess -> b"
            )
        )
    }

    @Test
    fun ignoresNamesOutsideTheExactApplicationPackageBoundary() {
        assertEquals(
            emptyList<String>(),
            audit(
                "com.estatia.realestate.apps2.SecretStore -> " +
                    "com.estatia.realestate.apps2.SecretStore:"
            )
        )
    }

    @Test
    fun preservesExplicitAllowlist() {
        assertEquals(
            emptyList<String>(),
            audit(
                "com.estatia.realestate.apps.security.ApiKeyValidator -> " +
                    "com.estatia.realestate.apps.security.ApiKeyValidator:"
            )
        )
    }

    @Test
    fun ignoresOrdinaryApplicationClassesEvenWhenTheyRemainUnchanged() {
        assertEquals(
            emptyList<String>(),
            audit(
                "com.estatia.realestate.apps.analytics.MetricsModule -> " +
                    "com.estatia.realestate.apps.analytics.MetricsModule:"
            )
        )
    }
}
