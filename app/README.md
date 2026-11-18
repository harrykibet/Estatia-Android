# app

## Overview
The `app` module is the entry point for the Estatia Android application. It orchestrates the various feature modules, sets up the global navigation host, and provides the top-level application and activity classes.

## Responsibilities
- **Navigation**: Defines the `EstatiaNavHost` which connects all feature graphs.
- **Dependency Injection**: Sets up the Hilt Singleton Component and provides app-wide dependencies.
- **Global UI**: Implements the `EstatiaApp` Composable, including the bottom navigation bar and snackbar host.
- **Process Orchestration**: Manages app-level lifecycles, background synchronization, and deep link resolution.

## App State
The module uses a centralized `EstatiaAppState` class to manage navigation logic, network status monitoring, and unread notification states in a reactive way.

## Configuration Files

The `app` module requires two critical configuration files to enable remote backend services. These files are typically gitignored in production environments but are provided as baselines for development.

### AWS Amplify (`amplifyconfiguration.json`)
- **Location**: `app/src/main/res/raw/amplifyconfiguration.json`
- **Purpose**: This file contains the endpoint information, regions, and client IDs for AWS services managed via the Amplify SDK.
- **Note**: The version committed to source control is a **stub/placeholder** for local builds and review. It does not establish live AWS connectivity; replace it with environment-specific configuration generated from deployed resources before enabling production AWS operations.
- **Configured Services in the checked-in stub**:
    - **Auth**: Cognito User Pool and Identity Pool settings (replace placeholder IDs with deployed resources before production use).
    - **Storage**: S3 bucket information for property media uploads.
    - **API**: Not configured in the checked-in stub. The AppSync plugin is registered only when a valid GraphQL endpoint, region, and authorization mode are present under `api.plugins.awsAPIPlugin`.
    - **CloudWatch Logging**: Optional; enabled only when `amplifyconfiguration_logging.json` is supplied in the same raw-resource directory.
    - **Analytics**: Amazon Pinpoint analytics is disabled. AWS ended support for Pinpoint engagement analytics on October 30, 2026; use a supported destination for any future remote-event integration.

### Google Services (`google-services.json`)
- **Location**: `app/google-services.json`
- **Purpose**: Required by the Google Services Gradle plugin to enable integration with Google and Firebase services.
- **Used For**:
    - **Google Sign-In**: Providing the OIDC client ID used by the Auth layer.
    - **Maps SDK**: Associating the application with the correct Google Cloud Project for Map rendering.
    - **Firebase Support**: Enabling Crashlytics, Performance Monitoring, and Cloud Messaging (FCM).

## Dependency Graph
![Module Graph](module_graph.png)
