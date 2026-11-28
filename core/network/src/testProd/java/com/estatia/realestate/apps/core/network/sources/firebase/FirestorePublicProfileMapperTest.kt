package com.estatia.realestate.apps.core.network.sources.firebase

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class FirestorePublicProfileMapperTest {

    @Test
    fun mapsOnlyPublicDisplayFields() {
        val user = FirestorePublicProfileMapper.toEntity(
            userId = "public-user",
            fields = mapOf(
                "uid" to "different-client-value",
                "displayName" to "Example Person",
                "profilePictureUrl" to "https://cdn.example.test/avatar.png",
                "bio" to "Public biography",
                "profileCategory" to "PROPERTY_OWNER",
                "profileType" to "ADMIN",
                "email" to "private@example.test",
                "phoneNumber" to "+254700000001",
                "userType" to "ADMIN",
                "verificationLevel" to "TRUSTED_PARTNER",
                "followerCount" to 25
            )
        )

        assertEquals("public-user", user.userId)
        assertEquals("Example Person", user.name)
        assertEquals(
            "https://cdn.example.test/avatar.png",
            user.profilePictureUrl
        )
        assertEquals("Public biography", user.bio)
        assertEquals("PROPERTY_OWNER", user.userType)
        assertNull(user.email)
        assertNull(user.phoneNumber)
        assertEquals("NONE", user.verificationLevel)
        assertEquals(0, user.followerCount)
    }
}
