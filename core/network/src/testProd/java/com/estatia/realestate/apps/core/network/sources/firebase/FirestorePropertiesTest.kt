package com.estatia.realestate.apps.core.network.sources.firebase

import com.estatia.realestate.apps.core.architecture.annotations.Logic.Utility
import com.estatia.realestate.apps.core.common.exceptions.AppResult
import com.estatia.realestate.apps.core.common.exceptions.NetworkException
import com.estatia.realestate.apps.core.common.interfaces.IClock
import com.estatia.realestate.apps.core.network.core.ExponentialRetryPolicy
import com.estatia.realestate.apps.core.network.db_names.FirestoreCollections.SubCollections.LIKED_PROPERTIES
import com.estatia.realestate.apps.core.network.db_names.FirestoreCollections.USERS
import com.estatia.realestate.apps.core.network.interfaces.IExceptionMapper
import com.estatia.realestate.apps.core.testing.chaos.network.NetworkBehavior
import com.estatia.realestate.apps.core.testing.chaos.network.NetworkChaosController
import com.estatia.realestate.apps.core.testing_network.chaos.ChaosNetworkClient
import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
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

        val metricsTracker = mockk<com.estatia.realestate.apps.core.domain.analytics.IMetricsTracker>(relaxed = true)
        firestoreProperties = FirestoreProperties(database, storage, networkClient, metricsTracker)
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
