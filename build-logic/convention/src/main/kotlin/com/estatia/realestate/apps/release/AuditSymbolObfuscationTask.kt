package com.estatia.realestate.apps.release

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*

/**
 * LAW-038: Release Symbol Obfuscation Integrity.
 *
 * R8 mapping records use the form "original.class.Name -> obfuscated.Name:".
 * Original names are expected in this file for retracing. The audit therefore
 * checks whether a sensitive application class name remains readable on the
 * mapped (right-hand) side, rather than flagging its original name.
 */
abstract class AuditSymbolObfuscationTask : DefaultTask() {

    @get:InputFile
    abstract val mappingFile: RegularFileProperty

    @get:Input
    abstract val applicationPackage: Property<String>

    @get:Input
    abstract val forbiddenPatterns: ListProperty<String>

    @get:Input
    abstract val allowlist: ListProperty<String>

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun audit() {
        val file = mappingFile.get().asFile
        val patterns = forbiddenPatterns.get().map(::Regex)
        val allowed = allowlist.get().toSet()
        val violations = findRetainedSensitiveSymbols(
            mappingLines = file.readLines(),
            applicationPackage = applicationPackage.get(),
            forbiddenPatterns = patterns,
            allowlist = allowed
        )

        reportFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(violations.joinToString("\n"))
        }

        if (violations.isNotEmpty()) {
            throw GradleException(
                "LAW-038 Violation: Readable sensitive class name(s) remain after R8 obfuscation:\n" +
                    violations.take(10).joinToString("\n") +
                    (if (violations.size > 10) "\n... and ${violations.size - 10} more." else "") +
                    "\n\nFull report: ${reportFile.get().asFile.absolutePath}" +
                    "\nRECOMMENDED: Review R8 keep rules and ensure sensitive application classes are eligible for obfuscation."
            )
        }
    }
}

/**
 * Returns violations only for top-level R8 class mappings owned by the app.
 * Method and field mappings are indented and do not match the class-mapping
 * grammar. Third-party classes are outside the configured application package.
 */
internal fun findRetainedSensitiveSymbols(
    mappingLines: List<String>,
    applicationPackage: String,
    forbiddenPatterns: List<Regex>,
    allowlist: Set<String>
): List<String> {
    val normalizedPackage = applicationPackage.trim().trimEnd('.')
    require(normalizedPackage.isNotEmpty()) { "applicationPackage must not be blank" }
    val applicationPrefix = "$normalizedPackage."

    return mappingLines.mapIndexedNotNull { index, rawLine ->
        // R8 class mappings are unindented. Member mappings begin with whitespace.
        if (rawLine.isEmpty() || rawLine.first().isWhitespace()) {
            return@mapIndexedNotNull null
        }

        val line = rawLine.trimEnd()
        val mapping = R8_CLASS_MAPPING.matchEntire(line)
            ?: return@mapIndexedNotNull null
        val originalClass = mapping.groupValues[1]
        val obfuscatedClass = mapping.groupValues[2]

        if (!originalClass.startsWith(applicationPrefix)) {
            return@mapIndexedNotNull null
        }

        val originalSimpleName = originalClass.substringAfterLast('.')
        if (originalSimpleName in allowlist) {
            return@mapIndexedNotNull null
        }

        val matchingPattern = forbiddenPatterns.firstOrNull {
            it.containsMatchIn(originalSimpleName)
        } ?: return@mapIndexedNotNull null

        // A renamed package alone is insufficient if the sensitive class token
        // remains visible (for example, "a.SecretStore" or "a$SecretStore").
        val obfuscatedSimpleName = obfuscatedClass.substringAfterLast('.')
        if (!matchingPattern.containsMatchIn(obfuscatedSimpleName)) {
            return@mapIndexedNotNull null
        }

        "L${index + 1}: $originalClass -> $obfuscatedClass (matched ${matchingPattern.pattern})"
    }
}

private val R8_CLASS_MAPPING = Regex(
    """^([A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)*) -> ([A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)*):$"""
)
