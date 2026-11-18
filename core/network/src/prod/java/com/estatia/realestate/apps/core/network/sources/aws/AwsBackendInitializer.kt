package com.estatia.realestate.apps.core.network.sources.aws

import android.content.Context
import com.amplifyframework.api.aws.AWSApiPlugin
import com.amplifyframework.auth.cognito.AWSCognitoAuthPlugin
import com.amplifyframework.core.Amplify
import com.amplifyframework.logging.cloudwatch.AWSCloudWatchLoggingPlugin
import com.amplifyframework.storage.s3.AWSS3StoragePlugin
import com.estatia.realestate.apps.core.common.interfaces.IBackendInitializer
import com.estatia.realestate.apps.core.common.interfaces.ILogger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import com.estatia.realestate.apps.core.architecture.annotations.Logic.Utility

/**
 * Initializes only the AWS Amplify categories with matching application resources.
 *
 * The checked-in configuration currently enables Cognito and S3. AppSync and
 * CloudWatch are opt-in and require their own complete configuration. Pinpoint
 * analytics is intentionally not registered because AWS support has ended.
 */
@Utility
internal class AwsBackendInitializer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: ILogger
) : IBackendInitializer {

    override suspend fun initialize() {
        try {
            val configuration = readRawConfiguration("amplifyconfiguration")
            if (configuration == null) {
                logger.w("AwsBackend", "Amplify was not initialized: amplifyconfiguration.json is missing")
                return
            }

            val plan = AwsAmplifyPluginPlan.fromConfiguration(
                configuration = configuration,
                hasCloudWatchConfiguration = hasRawConfiguration("amplifyconfiguration_logging")
            )

            if (!plan.hasConfiguredPlugins) {
                logger.w("AwsBackend", "Amplify was not initialized: no configured plugin resources were found")
                return
            }

            if (plan.authentication) {
                Amplify.addPlugin(AWSCognitoAuthPlugin())
            } else {
                logger.w("AwsBackend", "Cognito plugin is disabled: auth.plugins.awsCognitoAuthPlugin is missing")
            }

            if (plan.api) {
                Amplify.addPlugin(AWSApiPlugin())
            } else {
                logger.w(
                    "AwsBackend",
                    "AppSync API plugin is disabled: add a valid GraphQL endpoint under api.plugins.awsAPIPlugin"
                )
            }

            if (plan.storage) {
                Amplify.addPlugin(AWSS3StoragePlugin())
            } else {
                logger.w("AwsBackend", "S3 plugin is disabled: storage.plugins.awsS3StoragePlugin is missing")
            }

            if (plan.cloudWatchLogging) {
                Amplify.addPlugin(AWSCloudWatchLoggingPlugin())
            } else {
                logger.i(
                    "AwsBackend",
                    "CloudWatch logging plugin is disabled: amplifyconfiguration_logging.json is not present"
                )
            }

            Amplify.configure(context)

            logger.i(
                "AwsBackend",
                "Amplify initialized (Cognito=" + plan.authentication + ", AppSync=" + plan.api +
                    ", S3=" + plan.storage + ", CloudWatch=" + plan.cloudWatchLogging + ")"
            )
            logger.w(
                "AwsBackend",
                "Pinpoint analytics is disabled because AWS support ended on October 30, 2026"
            )
        } catch (error: Exception) {
            // Configuration errors from Amplify plugins may be JSON/runtime exceptions,
            // not only AmplifyException. They must not terminate Application startup.
            logger.e("AwsBackend", "Could not initialize configured Amplify plugins", error)
        }
    }

    private fun hasRawConfiguration(resourceName: String): Boolean =
        context.resources.getIdentifier(resourceName, "raw", context.packageName) != 0

    private fun readRawConfiguration(resourceName: String): String? {
        val resourceId = context.resources.getIdentifier(resourceName, "raw", context.packageName)
        if (resourceId == 0) return null

        return context.resources.openRawResource(resourceId)
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
    }
}
