package com.estatia.realestate.apps.core.network.sources.firebase

import com.estatia.realestate.apps.core.common.exceptions.AppResult
import com.estatia.realestate.apps.core.common.exceptions.NetworkException
import com.estatia.realestate.apps.core.common.interfaces.IClock
import com.estatia.realestate.apps.core.domain.analytics.IMetricsTracker
import com.estatia.realestate.apps.core.network.core.ExponentialRetryPolicy
import com.estatia.realestate.apps.core.network.db_entities.CommentEntityModel
import com.estatia.realestate.apps.core.network.interfaces.IDatabaseErrorMapper
import com.estatia.realestate.apps.core.network.interfaces.IExceptionMapper
import com.estatia.realestate.apps.core.testing.chaos.network.NetworkBehavior
import com.estatia.realestate.apps.core.testing.chaos.network.NetworkChaosController
import com.estatia.realestate.apps.core.testing_network.chaos.ChaosNetworkClient
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.HttpsCallableReference
import com.google.firebase.functions.HttpsCallableResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class FirestoreCommentsTest {

    private lateinit var database: FirebaseFirestore
    private lateinit var functions: FirebaseFunctions
    private lateinit var auth: FirebaseAuth
    private lateinit var networkChaos: NetworkChaosController
    private lateinit var metricsTracker: IMetricsTracker
    private lateinit var firestoreComments: FirestoreComments

    @Before
    fun setup() {
        database = mockk()
        functions = mockk()
        auth = mockk()
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
        firestoreComments = FirestoreComments(
            database,
            functions,
            auth,
            networkClient,
            metricsTracker,
            mockk<IDatabaseErrorMapper>(relaxed = true)
        )
    }

    @Test
    fun `submitComment calls the trusted backend without caller author fields`() = runTest {
        networkChaos.script(NetworkBehavior.Success)
        stubAuthenticatedUser("user123")
        val callable = mockk<HttpsCallableReference>()
        val task = mockk<Task<HttpsCallableResult>>()
        every { functions.getHttpsCallable("submitPropertyComment") } returns callable
        every { callable.call(any()) } returns task
        coEvery { task.await() } returns mockk()

        mockkStatic("kotlinx.coroutines.tasks.TasksKt")
        try {
            val comment = CommentEntityModel(
                id = null,
                authorId = "user123",
                message = "Is this available?",
                propertyId = "property-123",
                authorName = "Untrusted client name",
                timeStamp = 1L
            )
            val result = firestoreComments.submitComment(comment)

            assertTrue(result is AppResult.Success)
            verify(exactly = 1) {
                callable.call(
                    match {
                        it is Map<*, *> &&
                            it.keys == setOf("propertyId", "commentId", "message") &&
                            it["propertyId"] == "property-123" &&
                            it["message"] == "Is this available?" &&
                            it["commentId"] is String
                    }
                )
            }
        } finally {
            unmockkStatic("kotlinx.coroutines.tasks.TasksKt")
        }
    }

    @Test
    fun `submitComment rejects author spoofing before invoking backend`() = runTest {
        networkChaos.script(NetworkBehavior.Success)
        stubAuthenticatedUser("authenticated-user")
        val comment = CommentEntityModel(
            id = null,
            authorId = "another-user",
            message = "Is this available?",
            propertyId = "property-123",
            authorName = "Spoofed name",
            timeStamp = 1L
        )

        val result = firestoreComments.submitComment(comment)

        assertTrue(result is AppResult.Error)
        verify(exactly = 0) { functions.getHttpsCallable(any()) }
    }

    private fun stubAuthenticatedUser(userId: String) {
        val firebaseUser = mockk<FirebaseUser>()
        every { auth.currentUser } returns firebaseUser
        every { firebaseUser.uid } returns userId
    }
}
