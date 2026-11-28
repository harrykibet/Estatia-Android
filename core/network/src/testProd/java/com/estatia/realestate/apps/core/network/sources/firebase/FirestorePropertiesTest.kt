package com.estatia.realestate.apps.core.network.sources.firebase

import com.estatia.realestate.apps.core.architecture.annotations.Logic.Utility
import com.estatia.realestate.apps.core.common.exceptions.AppResult
import com.estatia.realestate.apps.core.common.exceptions.NetworkException
import com.estatia.realestate.apps.core.common.interfaces.IClock
import com.estatia.realestate.apps.core.network.core.ExponentialRetryPolicy
import com.estatia.realestate.apps.core.network.db_entities.PropertyContactEntity
import com.estatia.realestate.apps.core.network.db_entities.PropertyEntityModel
import com.estatia.realestate.apps.core.network.db_names.FirestoreCollections.PROPERTIES
import com.estatia.realestate.apps.core.network.db_names.FirestoreCollections.SubCollections.CONTACT
import com.estatia.realestate.apps.core.network.db_names.FirestoreCollections.SubCollections.LIKED_PROPERTIES
import com.estatia.realestate.apps.core.network.db_names.FirestoreCollections.USERS
import com.estatia.realestate.apps.core.network.interfaces.IExceptionMapper
import com.estatia.realestate.apps.core.testing.chaos.network.NetworkBehavior
import com.estatia.realestate.apps.core.testing.chaos.network.NetworkChaosController
import com.estatia.realestate.apps.core.testing_network.chaos.ChaosNetworkClient
import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.HttpsCallableReference
import com.google.firebase.functions.HttpsCallableResult
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.Timestamp
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.storage.FirebaseStorage
import android.content.Context
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
@Utility
class FirestorePropertiesTest {

    private lateinit var database: FirebaseFirestore
    private lateinit var storage: FirebaseStorage
    private lateinit var functions: FirebaseFunctions
    private lateinit var auth: FirebaseAuth
    private lateinit var context: Context
    private lateinit var networkChaos: NetworkChaosController
    private lateinit var metricsTracker: com.estatia.realestate.apps.core.domain.analytics.IMetricsTracker
    private lateinit var firestoreProperties: FirestoreProperties

    @Before
    fun setup() {
        database = mockk()
        storage = mockk()
        functions = mockk()
        auth = mockk()
        context = mockk()
        networkChaos = NetworkChaosController()

        val exceptionMapper = mockk<IExceptionMapper>(relaxed = true)
        every { exceptionMapper.map(any()) } answers {
            val throwable = it.invocation.args[0] as Throwable
            if (throwable is java.net.SocketTimeoutException) {
                NetworkException.Timeout
            } else {
                NetworkException.ConnectionFailed
            }
        }

        val clock = mockk<IClock>(relaxed = true)
        val retryPolicy = ExponentialRetryPolicy(exceptionMapper, clock)
        val networkClient = ChaosNetworkClient(
            networkChaos = networkChaos,
            exceptionMapper = exceptionMapper,
            retryPolicy = retryPolicy
        )

        metricsTracker = mockk(relaxed = true)
        firestoreProperties = FirestoreProperties(
            database,
            storage,
            functions,
            auth,
            context,
            networkClient,
            metricsTracker
        )
    }

    @Test
    fun `submitPropertyForReview calls the backend with only the property ID`() = runTest {
        stubAuthenticatedUser("owner-123")
        val callable = mockk<HttpsCallableReference>()
        val task = mockk<Task<HttpsCallableResult>>()
        val response = mockk<HttpsCallableResult>()
        val payload = slot<Any>()

        every {
            functions.getHttpsCallable("submitPropertyForReview")
        } returns callable
        every { callable.call(capture(payload)) } returns task
        coEvery { task.await() } returns response
        every { response.data } returns mapOf(
            "propertyId" to "property-123",
            "publicationStatus" to "pending_review",
            "submitted" to true
        )

        mockkStatic("kotlinx.coroutines.tasks.TasksKt")
        try {
            PropertyPublicationCallable.submitForReview(functions, "property-123")

            val request = payload.captured as? Map<*, *>
            assertEquals(mapOf("propertyId" to "property-123"), request)
            verify(exactly = 1) {
                functions.getHttpsCallable("submitPropertyForReview")
            }
        } finally {
            unmockkStatic("kotlinx.coroutines.tasks.TasksKt")
        }
    }

    @Test
    fun `submitPropertyForReview rejects a malformed backend response`() = runTest {
        val callable = mockk<HttpsCallableReference>()
        val task = mockk<Task<HttpsCallableResult>>()
        val response = mockk<HttpsCallableResult>()
        every {
            functions.getHttpsCallable("submitPropertyForReview")
        } returns callable
        every { callable.call(any()) } returns task
        coEvery { task.await() } returns response
        every { response.data } returns mapOf(
            "propertyId" to "another-property",
            "publicationStatus" to "published"
        )

        mockkStatic("kotlinx.coroutines.tasks.TasksKt")
        try {
            try {
                PropertyPublicationCallable.submitForReview(functions, "property-123")
                throw AssertionError("Expected malformed backend response to fail.")
            } catch (exception: Exception) {
                assertTrue(exception.message.orEmpty().contains("did not enter pending review"))
            }
        } finally {
            unmockkStatic("kotlinx.coroutines.tasks.TasksKt")
        }
    }

    @Test
    fun `likeProperty delegates state transition to the server callable`() = runTest {
        networkChaos.script(NetworkBehavior.Success, NetworkBehavior.Success)
        stubAuthenticatedUser("user123")
        val callable = mockk<HttpsCallableReference>()
        val task = mockk<Task<HttpsCallableResult>>()
        val payload = slot<Any>()
        every { functions.getHttpsCallable("setPropertyLike") } returns callable
        every { callable.call(capture(payload)) } returns task
        coEvery { task.await() } returns mockk()

        mockkStatic("kotlinx.coroutines.tasks.TasksKt")
        try {
            val result = firestoreProperties.likeProperty(
                userId = "user123",
                propertyId = "property-123"
            )
            assertTrue(result is AppResult.Success)
            verify(exactly = 1) { callable.call(any()) }
            val request = payload.captured as? Map<*, *>
            assertTrue("Callable payload must be a map", request != null)
            assertEquals("property-123", request?.get("propertyId"))
            assertEquals(true, request?.get("liked"))
        } finally {
            unmockkStatic("kotlinx.coroutines.tasks.TasksKt")
        }
    }

    @Test
    fun `likeProperty rejects a mismatched user ID before calling backend`() = runTest {
        networkChaos.script(NetworkBehavior.Success)
        stubAuthenticatedUser("authenticated-user")

        val result = firestoreProperties.likeProperty(
            userId = "another-user",
            propertyId = "property-123"
        )

        assertTrue(result is AppResult.Error)
        verify(exactly = 0) { functions.getHttpsCallable(any()) }
    }

    @Test
    fun `recordView and recordShare use idempotent backend events`() = runTest {
        networkChaos.script(NetworkBehavior.Success)
        stubAuthenticatedUser("user123")
        val callable = mockk<HttpsCallableReference>()
        val task = mockk<Task<HttpsCallableResult>>()
        every {
            functions.getHttpsCallable("recordPropertyEngagement")
        } returns callable
        val payloads = mutableListOf<Any>()
        every { callable.call(capture(payloads)) } returns task
        coEvery { task.await() } returns mockk()

        mockkStatic("kotlinx.coroutines.tasks.TasksKt")
        try {
            val viewResult = firestoreProperties.recordView("property-123")
            val shareResult = firestoreProperties.recordShare("property-123")

            assertTrue(viewResult is AppResult.Success)
            assertTrue(shareResult is AppResult.Success)
            verify(exactly = 2) {
                functions.getHttpsCallable("recordPropertyEngagement")
            }
            val requests = payloads.mapNotNull { it as? Map<*, *> }
            assertEquals(2, requests.size)
            val viewRequest = requests.single { it["eventType"] == "view" }
            val shareRequest = requests.single { it["eventType"] == "share" }
            for (request in listOf(viewRequest, shareRequest)) {
                assertEquals("property-123", request["propertyId"])
                assertTrue(request["eventId"] is String)
            }
        } finally {
            unmockkStatic("kotlinx.coroutines.tasks.TasksKt")
        }
    }

    @Test
    fun `recordView rejects unauthenticated requests before calling backend`() = runTest {
        networkChaos.script(NetworkBehavior.Success)
        every { auth.currentUser } returns null

        val result = firestoreProperties.recordView("property-123")

        assertTrue(result is AppResult.Error)
        verify(exactly = 0) { functions.getHttpsCallable(any()) }
    }

    @Test
    fun `getPropertyById preserves public property when gated contact read is denied`() = runTest {
        networkChaos.script(NetworkBehavior.Success)
        mockkStatic("kotlinx.coroutines.tasks.TasksKt")
        try {
            val propertyId = "public-property-123"
            val propertiesCollection = mockk<CollectionReference>()
            val propertyDocument = mockk<DocumentReference>()
            val propertyTask = mockk<Task<DocumentSnapshot>>()
            val propertySnapshot = mockk<DocumentSnapshot>()
            val contactCollection = mockk<CollectionReference>()
            val contactDocument = mockk<DocumentReference>()
            val contactTask = mockk<Task<DocumentSnapshot>>()

            every { database.collection(PROPERTIES) } returns propertiesCollection
            every { propertiesCollection.document(propertyId) } returns propertyDocument
            every { propertyDocument.get() } returns propertyTask
            coEvery { propertyTask.await() } returns propertySnapshot
            every { propertySnapshot.exists() } returns true
            every { propertySnapshot.id } returns propertyId
            every { propertySnapshot.data } returns mapOf("title" to "Public property")

            every { propertyDocument.collection(CONTACT) } returns contactCollection
            every { contactCollection.document("info") } returns contactDocument
            every { contactDocument.get() } returns contactTask
            coEvery { contactTask.await() } throws FirebaseFirestoreException(
                "Contact details are not readable by this user.",
                FirebaseFirestoreException.Code.PERMISSION_DENIED,
                null,
            )

            val result = firestoreProperties.getPropertyById(propertyId)

            assertTrue(result is AppResult.Success)
            val property = (result as AppResult.Success).data
            assertEquals(propertyId, property.id)
            assertEquals("Public property", property.title)
            assertEquals(null, property.contact)
            verify(exactly = 1) {
                metricsTracker.incrementCounter("network.properties.contact_read_denied")
            }
        } finally {
            unmockkStatic("kotlinx.coroutines.tasks.TasksKt")
        }
    }

    @Test
    fun `fetchLikedProperties retries a transient timeout and then succeeds`() = runTest {
        networkChaos.script(NetworkBehavior.Timeout, NetworkBehavior.Success)

        mockkStatic("kotlinx.coroutines.tasks.TasksKt")
        try {
            stubAuthenticatedUser("user123")
            stubLegacyMigrationComplete()
            val likedCollection = stubEmptyLikedPropertiesQuery("user123")

            val result = firestoreProperties.fetchLikedProperties("user123")

            assertTrue(result is AppResult.Success)
            verify(exactly = 1) { likedCollection.get() }
        } finally {
            unmockkStatic("kotlinx.coroutines.tasks.TasksKt")
        }
    }

    @Test
    fun `fetchLikedProperties maps malformed responses after the Firestore operation succeeds`() = runTest {
        // The retry policy allows three attempts for PROPERTY_FEED; script all three
        // responses so the test exercises malformed-response handling, not an unstubbed mock.
        networkChaos.script(
            NetworkBehavior.MalformedResponse,
            NetworkBehavior.MalformedResponse,
            NetworkBehavior.MalformedResponse
        )

        mockkStatic("kotlinx.coroutines.tasks.TasksKt")
        try {
            stubAuthenticatedUser("user123")
            stubLegacyMigrationComplete()
            val likedCollection = stubEmptyLikedPropertiesQuery("user123")

            val result = firestoreProperties.fetchLikedProperties("user123")

            assertTrue(result is AppResult.Error)
            assertEquals(
                NetworkException.ConnectionFailed,
                (result as AppResult.Error).exception
            )
            verify(exactly = 3) { likedCollection.get() }
        } finally {
            unmockkStatic("kotlinx.coroutines.tasks.TasksKt")
        }
    }


    @Test
    fun `Firestore Timestamp property dates normalize to epoch milliseconds without losing cursor precision`() {
        val timestamp = Timestamp(1_700_000_000L, 123_456_789)

        val property = FirestorePropertyMapper.toEntity(
            documentId = "property-123",
            fields = mapOf(
                "title" to "Timestamp-backed listing",
                "createdAt" to timestamp,
                "price" to 125_000,
            ),
        )

        assertEquals("property-123", property.id)
        assertEquals("Timestamp-backed listing", property.title)
        assertEquals(125_000.0, checkNotNull(property.price), 0.0)
        assertEquals(1_700_000_000_123L, checkNotNull(property.createdAt))

        val cursor = FirestorePropertyMapper.toCursor("property-123", timestamp)
        assertEquals(1_700_000_000_123L, cursor.createdAt)
        assertEquals(1_700_000_000L, checkNotNull(cursor.createdAtSeconds))
        assertEquals(123_456_789, checkNotNull(cursor.createdAtNanoseconds))

        val queryValue = FirestorePropertyMapper.toFirestoreCursorValue(cursor) as Timestamp
        assertEquals(timestamp.seconds, queryValue.seconds)
        assertEquals(timestamp.nanoseconds, queryValue.nanoseconds)
    }

    @Test
    fun `legacy numeric property dates remain milliseconds and keep numeric cursor type`() {
        val createdAt = 1_700_000_000_123L

        val property = FirestorePropertyMapper.toEntity(
            documentId = "legacy-property",
            fields = mapOf("createdAt" to createdAt),
        )
        val cursor = FirestorePropertyMapper.toCursor("legacy-property", createdAt)

        assertEquals(createdAt, checkNotNull(property.createdAt))
        assertEquals(createdAt, cursor.createdAt)
        assertEquals(null, cursor.createdAtSeconds)
        assertEquals(null, cursor.createdAtNanoseconds)
        assertEquals(createdAt, FirestorePropertyMapper.toFirestoreCursorValue(cursor))
    }

    @Test
    fun `unsupported Firestore property date types fail with a field-specific error`() {
        try {
            FirestorePropertyMapper.toEntity(
                documentId = "invalid-property",
                fields = mapOf("createdAt" to "not-a-timestamp"),
            )
            throw AssertionError("Expected an invalid Firestore createdAt value to fail.")
        } catch (exception: IllegalArgumentException) {
            assertTrue(exception.message.orEmpty().contains("createdAt"))
        }
    }

    @Test
    fun `inactive public draft write payload uses authenticated owner and omits private fields`() {
        val property = PropertyEntityModel(
            id = "caller-property-id",
            title = "Public listing",
            ownerId = "forged-owner",
            ownerName = "Unverified owner name",
            contact = PropertyContactEntity(
                phone = "+254700000001",
                email = "private@example.test"
            ),
            active = true,
            imageUrl = listOf("https://attacker.example.test/unverified.jpg"),
            directVideoUrls = listOf("https://attacker.example.test/unverified.mp4"),
            hlsUrl = listOf("https://attacker.example.test/unverified.m3u8"),
            viewsCount = 90,
            sharesCount = 80,
            likesCount = 70,
            commentsCount = 60,
            matchScore = 0.99f,
            createdAt = 1L
        )

        val payload = FirestorePropertyWriteMapper.toInactiveDraft(
            property = property,
            propertyId = "server-selected-id",
            authenticatedOwnerId = "authenticated-owner",
            uploadAttemptId = "attempt-123"
        )

        assertEquals("authenticated-owner", payload["ownerId"])
        assertEquals("attempt-123", payload["uploadAttemptId"])
        assertEquals("server-selected-id", payload["id"])
        assertEquals(false, payload["active"])
        assertEquals("Public listing", payload["title"])
        assertEquals(emptyList<String>(), payload["imageUrl"])
        assertEquals(emptyList<String>(), payload["directVideoUrls"])
        assertEquals(emptyList<String>(), payload["hlsUrl"])
        assertFalse(payload.containsKey("ownerName"))
        assertFalse(payload.containsKey("contact"))
        assertFalse(payload.containsKey("contactPhone"))
        assertFalse(payload.containsKey("contactEmail"))
        assertFalse(payload.containsKey("viewsCount"))
        assertFalse(payload.containsKey("sharesCount"))
        assertFalse(payload.containsKey("likesCount"))
        assertFalse(payload.containsKey("commentsCount"))
        assertFalse(payload.containsKey("matchScore"))
        assertFalse(payload["createdAt"] == 1L)
    }

    @Test
    fun `media finalization payload cannot change owner or publication state`() {
        val payload = FirestorePropertyWriteMapper.toMediaFinalization(
            imageUrls = listOf("https://cdn.example.test/image.jpg"),
            videoUrls = listOf("https://cdn.example.test/video.mp4")
        )

        assertEquals(
            setOf("imageUrl", "directVideoUrls", "hlsUrl", "uploadAttemptId", "updatedAt"),
            payload.keys
        )
        assertEquals(listOf("https://cdn.example.test/image.jpg"), payload["imageUrl"])
        assertEquals(listOf("https://cdn.example.test/video.mp4"), payload["directVideoUrls"])
    }

    @Test
    fun `public property mapper ignores legacy embedded contact data`() {
        val property = FirestorePropertyMapper.toEntity(
            documentId = "legacy-property",
            fields = mapOf(
                "title" to "Legacy listing",
                "contact" to mapOf(
                    "phone" to "+254700000001",
                    "email" to "private@example.test"
                )
            )
        )

        assertEquals(null, property.contact)
    }

    @Test
    fun `private property contact requires two matching non-empty identities`() {
        assertFalse(FirestorePropertyReadPolicy.canReadPrivateContact(null, null))
        assertFalse(FirestorePropertyReadPolicy.canReadPrivateContact("user-1", null))
        assertFalse(FirestorePropertyReadPolicy.canReadPrivateContact(null, "user-1"))
        assertFalse(FirestorePropertyReadPolicy.canReadPrivateContact("", ""))
        assertFalse(FirestorePropertyReadPolicy.canReadPrivateContact("user-1", "user-2"))
        assertTrue(FirestorePropertyReadPolicy.canReadPrivateContact("user-1", "user-1"))
    }

    private fun stubAuthenticatedUser(userId: String) {
        val firebaseUser = mockk<FirebaseUser>()
        every { auth.currentUser } returns firebaseUser
        every { firebaseUser.uid } returns userId
    }

    private fun stubLegacyMigrationComplete() {
        val callable = mockk<HttpsCallableReference>()
        val task = mockk<Task<HttpsCallableResult>>()
        val result = mockk<HttpsCallableResult>()
        every {
            functions.getHttpsCallable("migrateLegacyPropertyLikes")
        } returns callable
        every { callable.call(any()) } returns task
        coEvery { task.await() } returns result
        every { result.data } returns mapOf("remaining" to 0)
    }

    private fun stubEmptyLikedPropertiesQuery(userId: String): CollectionReference {
        val usersCollection = mockk<CollectionReference>()
        val userDocument = mockk<DocumentReference>()
        val likedCollection = mockk<CollectionReference>()
        val likedTask = mockk<Task<QuerySnapshot>>()
        val likedSnapshot = mockk<QuerySnapshot>()

        every { database.collection(USERS) } returns usersCollection
        every { usersCollection.document(userId) } returns userDocument
        every { userDocument.collection(LIKED_PROPERTIES) } returns likedCollection
        every { likedCollection.get() } returns likedTask
        coEvery { likedTask.await() } returns likedSnapshot
        every { likedSnapshot.documents } returns emptyList()

        return likedCollection
    }
}
