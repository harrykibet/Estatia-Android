package com.estatia.realestate.apps.core.network.utils

/**
 * Service categories used to select client-side API-key format validation rules.
 *
 * These values are validation categories only. They are not Secret Manager
 * resource names and do not provide or authorize access to private credentials.
 * Private provider credentials must remain on trusted server infrastructure.
 */
enum class ServiceNames {
    PAYMENTS,
    AUTH,
    GOOGLE_PLAY_INTEGRITY,
    AI_ML;

    companion object {
        fun fromServiceName(service: String) =
            entries.firstOrNull { it.name.equals(service, ignoreCase = true) }
    }
}
