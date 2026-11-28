package com.estatia.realestate.apps.core.network.sources.firebase

import com.estatia.realestate.apps.core.network.db_entities.PropertyEntityModel
import com.google.firebase.firestore.FieldValue

/**
 * Explicit public-document write boundary for properties/{propertyId}.
 *
 * Contact data, caller-supplied owner display names, moderation state,
 * engagement counters and query-derived scores are intentionally excluded.
 */
internal object FirestorePropertyWriteMapper {

    fun toInactiveDraft(
        property: PropertyEntityModel,
        propertyId: String,
        authenticatedOwnerId: String,
        uploadAttemptId: String
    ): Map<String, Any> = buildMap {
        put("schemaVersion", property.schemaVersion)
        put("id", propertyId)
        put("ownerId", authenticatedOwnerId)
        // Unique marker prevents a failed attempt from deleting an existing document
        // and lets reconciliation identify drafts whose transaction outcome was unknown.
        put("uploadAttemptId", uploadAttemptId)
        put("title", property.title)
        put("imageUrl", property.imageUrl.orEmpty())
        put("directVideoUrls", property.directVideoUrls.orEmpty())
        put("hlsUrl", property.hlsUrl.orEmpty())
        put("video", property.video)
        put("active", false)
        put("available", property.available)
        put("createdAt", FieldValue.serverTimestamp())
        put("updatedAt", FieldValue.serverTimestamp())

        property.description?.let { put("description", it) }
        property.price?.let { put("price", it) }
        property.latitude?.let { put("latitude", it) }
        property.longitude?.let { put("longitude", it) }
        property.county?.let { put("county", it) }
        property.propertyType?.let { put("propertyType", it) }
        property.bedrooms?.let { put("bedrooms", it) }
        property.bathrooms?.let { put("bathrooms", it) }
        property.areaSize?.let { put("areaSize", it) }
        property.amenities?.let { put("amenities", it) }
        property.features?.let { put("features", it) }
        property.depositAmount?.let { put("depositAmount", it) }
        property.address?.let { put("address", it) }
        property.availableFrom?.let { put("availableFrom", it) }
        property.leaseTerms?.let { put("leaseTerms", it) }
    }

    fun toMediaFinalization(
        imageUrls: List<String>,
        videoUrls: List<String>
    ): Map<String, Any> = mapOf(
        "imageUrl" to imageUrls,
        "directVideoUrls" to videoUrls,
        // HLS generation remains a trusted server-side responsibility.
        "hlsUrl" to emptyList<String>(),
        "uploadAttemptId" to FieldValue.delete(),
        "updatedAt" to FieldValue.serverTimestamp()
    )
}
