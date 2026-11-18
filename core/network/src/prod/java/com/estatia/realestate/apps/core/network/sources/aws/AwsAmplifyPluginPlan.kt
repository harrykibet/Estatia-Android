package com.estatia.realestate.apps.core.network.sources.aws

import com.estatia.realestate.apps.core.architecture.annotations.Logic.Utility
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Describes the Amplify plugins that can safely be registered from the resources
 * actually shipped by the application.
 *
 * Pinpoint is deliberately excluded: AWS ended support for its engagement and
 * analytics capabilities on October 30, 2026.
 */
@Utility
internal data class AwsAmplifyPluginPlan(
    val authentication: Boolean,
    val api: Boolean,
    val storage: Boolean,
    val cloudWatchLogging: Boolean
) {
    val hasConfiguredPlugins: Boolean
        get() = authentication || api || storage || cloudWatchLogging

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
        }

        fun fromConfiguration(
            configuration: String,
            hasCloudWatchConfiguration: Boolean
        ): AwsAmplifyPluginPlan {
            val root = json.parseToJsonElement(configuration).jsonObject

            return AwsAmplifyPluginPlan(
                authentication = root.plugin("auth", "awsCognitoAuthPlugin")?.isNotEmpty() == true,
                api = root.plugin("api", "awsAPIPlugin")?.hasConfiguredGraphQlEndpoint() == true,
                storage = root.plugin("storage", "awsS3StoragePlugin")?.isNotEmpty() == true,
                cloudWatchLogging = hasCloudWatchConfiguration
            )
        }

        private fun JsonObject.plugin(category: String, pluginName: String): JsonObject? {
            val categoryConfig = this[category] as? JsonObject ?: return null
            val plugins = categoryConfig["plugins"] as? JsonObject ?: return null
            return plugins[pluginName] as? JsonObject
        }

        private fun JsonObject.hasConfiguredGraphQlEndpoint(): Boolean =
            values.any { endpointElement ->
                val endpoint = endpointElement as? JsonObject ?: return@any false
                val endpointType = endpoint.stringValue("endpointType")
                val url = endpoint.stringValue("endpoint")
                val region = endpoint.stringValue("region")
                val authorizationType = endpoint.stringValue("authorizationType")

                endpointType.equals("GraphQL", ignoreCase = true) &&
                    url?.startsWith("https://", ignoreCase = true) == true &&
                    !region.isNullOrBlank() &&
                    !authorizationType.isNullOrBlank() &&
                    (authorizationType != "API_KEY" || !endpoint.stringValue("apiKey").isNullOrBlank())
            }

        private fun JsonObject.stringValue(key: String): String? =
            (this[key] as? JsonElement)?.jsonPrimitive?.contentOrNull
    }
}
