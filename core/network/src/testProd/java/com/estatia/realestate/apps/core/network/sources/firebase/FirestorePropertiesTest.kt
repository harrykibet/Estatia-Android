package com.estatia.realestate.apps.core.network.sources.firebase

import com.estatia.realestate.apps.core.architecture.annotations.Logic.Utility
import com.estatia.realestate.apps.core.common.exceptions.AppResult
import com.estatia.realestate.apps.core.common.exceptions.NetworkException
import com.estatia.realestate.apps.core.common.interfaces.IClock
import com.estatia.realestate.apps.core.network.core.ExponentialRetryPolicy
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
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.Timestamp
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.storage.FirebaseStorage
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
@Utility
class FirestorePropertiesTest {

    private lateinit var database: FirebaseFirestore
    private lateinit var storage: FirebaseStorage
    private lateinit var networkChaos: NetworkChaosController
    private lateinit var metricsTracker: com.estatia.realestate.apps.core.domain.analytics.IMetricsTracker
    private lateinit var firestoreProperties: FirestoreProperties

    @Before
    fun setup() {
        database = mockk()
        storage = mockk()
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
        firestoreProperties = FirestoreProperties(database, storage, networkClient, metricsTracker)
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
                null,
                FirebaseFirestoreException.Code.PERMISSION_DENIED,
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
