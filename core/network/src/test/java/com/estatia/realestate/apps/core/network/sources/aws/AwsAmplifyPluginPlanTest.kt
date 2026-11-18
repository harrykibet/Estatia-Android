package com.estatia.realestate.apps.core.network.sources.aws

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AwsAmplifyPluginPlanTest {

    @Test
    fun checkedInStubEnablesOnlyConfiguredCognitoAndS3Plugins() {
        val configuration = """
            {
              "auth": {
                "plugins": {
                  "awsCognitoAuthPlugin": {
                    "CognitoUserPool": {
                      "Default": {
                        "PoolId": "pool-placeholder",
                        "AppClientId": "client-placeholder",
                        "Region": "us-west-1"
                      }
                    }
                  }
                }
              },
              "storage": {
                "plugins": {
                  "awsS3StoragePlugin": {
                    "bucket": "estatia-property-assets",
                    "region": "us-west-1"
                  }
                }
              }
            }
        """.trimIndent()

        val plan = AwsAmplifyPluginPlan.fromConfiguration(
            configuration = configuration,
            hasCloudWatchConfiguration = false
        )

        assertTrue(plan.authentication)
        assertFalse(plan.api)
        assertTrue(plan.storage)
        assertFalse(plan.cloudWatchLogging)
        assertTrue(plan.hasConfiguredPlugins)
    }

    @Test
    fun appSyncRequiresACompleteGraphQlEndpoint() {
        val configuration = """
            {
              "api": {
                "plugins": {
                  "awsAPIPlugin": {
                    "estatia": {
                      "endpointType": "GraphQL",
                      "endpoint": "https://example.appsync-api.us-west-1.amazonaws.com/graphql",
                      "region": "us-west-1",
                      "authorizationType": "AMAZON_COGNITO_USER_POOLS"
                    }
                  }
                }
              }
            }
        """.trimIndent()

        val plan = AwsAmplifyPluginPlan.fromConfiguration(
            configuration = configuration,
            hasCloudWatchConfiguration = false
        )

        assertTrue(plan.api)
    }

    @Test
    fun appSyncRejectsAnIncompleteGraphQlEndpoint() {
        val configuration = """
            {
              "api": {
                "plugins": {
                  "awsAPIPlugin": {
                    "estatia": {
                      "endpointType": "GraphQL",
                      "region": "us-west-1",
                      "authorizationType": "AWS_IAM"
                    }
                  }
                }
              }
            }
        """.trimIndent()

        val plan = AwsAmplifyPluginPlan.fromConfiguration(
            configuration = configuration,
            hasCloudWatchConfiguration = false
        )

        assertFalse(plan.api)
        assertFalse(plan.hasConfiguredPlugins)
    }

    @Test
    fun cloudWatchRequiresItsSeparateConfigurationResource() {
        val baseConfiguration = """
            {
              "auth": {
                "plugins": {
                  "awsCognitoAuthPlugin": {}
                }
              }
            }
        """.trimIndent()

        val withoutLogging = AwsAmplifyPluginPlan.fromConfiguration(
            configuration = baseConfiguration,
            hasCloudWatchConfiguration = false
        )
        val withLogging = AwsAmplifyPluginPlan.fromConfiguration(
            configuration = baseConfiguration,
            hasCloudWatchConfiguration = true
        )

        assertFalse(withoutLogging.cloudWatchLogging)
        assertTrue(withLogging.cloudWatchLogging)
    }
}
