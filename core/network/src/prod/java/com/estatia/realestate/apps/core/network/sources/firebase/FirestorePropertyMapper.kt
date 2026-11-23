package com.estatia.realestate.apps.core.network.sources.firebase

import com.estatia.realestate.apps.core.model.property.PropertyCursor
import com.estatia.realestate.apps.core.network.db_entities.PropertyContactEntity
import com.estatia.realestate.apps.core.network.db_entities.PropertyEntityModel
import com.estatia.realestate.apps.core.network.db_names.FirestoreFields
import com.google.firebase.Timestamp

/**
 * Converts Firestore's native document values into the transport model used by
 * the rest of the network layer.
 *
 * Firestore timestamps are native Timestamp values. PropertyEntityModel keeps
 * createdAt as epoch milliseconds because that is the representation shared by
 * the domain, Room cache, demo source, and AppSync adapter. Normalize the
 * Firestore-specific representation here rather than weakening that contract.
 */
internal object FirestorePropertyMapper {

    private data class CreatedAtValue(
        val epochMillis: Long,
        val timestampSeconds: Long? = null,
        val timestampNanoseconds: Int? = null,
    )

    fun toEntity(
        documentId: String,
        fields: Map<String, Any?>,
    ): PropertyEntityModel {
        val contactFields = fields["contact"] as? Map<*, *>

        return PropertyEntityModel(
            schemaVersion = fields.int("schemaVersion") ?: 1,
            id = fields.string("id") ?: documentId,
            title = fields.string("title") ?: "",
            description = fields.string("description"),
            price = fields.double("price"),
            imageUrl = fields.stringList("imageUrl"),
            directVideoUrls = fields.stringList("directVideoUrls"),
            hlsUrl = fields.stringList("hlsUrl"),
            video = fields.boolean("video") ?: false,
            latitude = fields.double("latitude"),
            longitude = fields.double("longitude"),
            createdAt = readCreatedAt(fields[FirestoreFields.CREATED_AT])?.epochMillis,
            ownerId = fields.string("ownerId"),
            ownerName = fields.string("ownerName"),
            contact = contactFields?.let {
                PropertyContactEntity(
                    phone = it["phone"] as? String,
                    email = it["email"] as? String,
                )
            },
            county = fields.string("county"),
            active = fields.boolean("active") ?: true,
            viewsCount = fields.int("viewsCount") ?: 0,
            sharesCount = fields.int("sharesCount") ?: 0,
            likesCount = fields.int("likesCount") ?: 0,
            commentsCount = fields.int("commentsCount") ?: 0,
            matchScore = fields.double("matchScore")?.toFloat() ?: 0.5f,
            propertyType = fields.string("propertyType"),
            bedrooms = fields.int("bedrooms"),
            bathrooms = fields.int("bathrooms"),
            areaSize = fields.double("areaSize"),
            amenities = fields.stringList("amenities"),
            features = fields.string("features"),
            depositAmount = fields.double("depositAmount"),
            address = fields.string("address"),
            availableFrom = fields.string("availableFrom"),
            leaseTerms = fields.string("leaseTerms"),
            available = fields.boolean("available") ?: true,
        )
    }

    /**
     * Builds a domain cursor without losing Firestore Timestamp nanoseconds.
     * Numeric legacy values remain epoch milliseconds and retain their original
     * query type when the next page is requested.
     */
    fun toCursor(
        documentId: String,
        rawCreatedAt: Any?,
    ): PropertyCursor {
        val createdAt = readCreatedAt(rawCreatedAt)
        return PropertyCursor(
            createdAt = createdAt?.epochMillis ?: 0L,
            documentId = documentId,
            createdAtSeconds = createdAt?.timestampSeconds,
            createdAtNanoseconds = createdAt?.timestampNanoseconds,
        )
    }

    /**
     * Reconstructs the exact value type expected by Firestore startAfter().
     */
    fun toFirestoreCursorValue(cursor: PropertyCursor): Any {
        val seconds = cursor.createdAtSeconds
        val nanoseconds = cursor.createdAtNanoseconds

        require((seconds == null) == (nanoseconds == null)) {
            "Firestore cursor seconds and nanoseconds must either both be present or both be absent."
        }

        return if (seconds != null) {
            Timestamp(seconds, checkNotNull(nanoseconds))
        } else {
            cursor.createdAt
        }
    }

    private fun readCreatedAt(value: Any?): CreatedAtValue? = when (value) {
        null -> null
        is Timestamp -> CreatedAtValue(
            epochMillis = value.toDate().time,
            timestampSeconds = value.seconds,
            timestampNanoseconds = value.nanoseconds,
        )
        is Number -> CreatedAtValue(epochMillis = value.toLong())
        else -> throw IllegalArgumentException(
            "Firestore '" + FirestoreFields.CREATED_AT +
                "' must be a Timestamp or epoch-millisecond number; found " +
                value.javaClass.name + ".",
        )
    }

    private fun Map<String, Any?>.string(field: String): String? =
        this[field] as? String

    private fun Map<String, Any?>.boolean(field: String): Boolean? =
        this[field] as? Boolean

    private fun Map<String, Any?>.int(field: String): Int? =
        (this[field] as? Number)?.toInt()

    private fun Map<String, Any?>.double(field: String): Double? =
        (this[field] as? Number)?.toDouble()

    private fun Map<String, Any?>.stringList(field: String): List<String>? =
        (this[field] as? List<*>)?.map { value ->
            value as? String
                ?: throw IllegalArgumentException("Firestore '" + field + "' must contain only strings.")
        }
}
