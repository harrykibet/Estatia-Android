package com.estatia.realestate.apps.core.network.sources.firebase

/**
 * Guards access to the separately stored private property contact document.
 *
 * Nullable equality alone is not sufficient: null == null must never grant
 * an anonymous caller access to a legacy document with no ownerId.
 */
internal object FirestorePropertyReadPolicy {
    fun canReadPrivateContact(
        authenticatedUid: String?,
        propertyOwnerId: String?
    ): Boolean =
        !authenticatedUid.isNullOrBlank() &&
            !propertyOwnerId.isNullOrBlank() &&
            authenticatedUid == propertyOwnerId
}
