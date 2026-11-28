package com.estatia.realestate.apps.core.network.sources.firebase

import com.estatia.realestate.apps.core.common.exceptions.DatabaseException
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.tasks.await

/**
 * Client contract for the backend-owned property submission transition.
 *
 * The property itself and its Storage objects must already be finalized.
 * The backend derives the owner from Firebase Authentication and verifies the
 * private contact record, listing fields and Storage object paths.
 */
internal object PropertyPublicationCallable {

    suspend fun submitForReview(
        functions: FirebaseFunctions,
        propertyId: String
    ) {
        if (propertyId.isBlank() || propertyId.contains("/")) {
            throw DatabaseException.InvalidData("A valid property ID is required.")
        }

        val response = functions
            .getHttpsCallable("submitPropertyForReview")
            .call(mapOf("propertyId" to propertyId))
            .await()

        val payload = response.data as? Map<*, *>
            ?: throw DatabaseException.InvalidData(
                "Property submission returned an invalid response."
            )
        if (
            payload["propertyId"] != propertyId ||
            payload["publicationStatus"] != "pending_review"
        ) {
            throw DatabaseException.InvalidData(
                "Property submission did not enter pending review."
            )
        }
    }
}
