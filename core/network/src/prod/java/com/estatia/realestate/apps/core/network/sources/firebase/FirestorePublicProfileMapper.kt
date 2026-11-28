package com.estatia.realestate.apps.core.network.sources.firebase

import com.estatia.realestate.apps.core.network.db_entities.UserEntityModel

/**
 * Maps the explicitly public profile document shape into the existing
 * transport model without copying private or privileged document fields.
 */
internal object FirestorePublicProfileMapper {

    /**
     * Maps display-safe attributes only.
     *
     * @param userId Firestore document ID, which is the authenticated UID.
     * @param fields Public profile document fields.
     * @return User transport model with private fields left unset.
     */
    fun toEntity(
        userId: String,
        fields: Map<String, Any>
    ): UserEntityModel = UserEntityModel(
        userId = userId,
        name = fields["displayName"] as? String,
        profilePictureUrl = fields["profilePictureUrl"] as? String,
        bio = fields["bio"] as? String
    )
}
