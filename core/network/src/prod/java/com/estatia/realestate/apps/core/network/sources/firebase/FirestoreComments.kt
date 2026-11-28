package com.estatia.realestate.apps.core.network.sources.firebase

import com.estatia.realestate.apps.core.architecture.annotations.Logic.Utility
import com.estatia.realestate.apps.core.common.exceptions.AppResult
import com.estatia.realestate.apps.core.common.exceptions.AuthException
import com.estatia.realestate.apps.core.domain.analytics.IMetricsTracker
import com.estatia.realestate.apps.core.network.core.RetryConfigs
import com.estatia.realestate.apps.core.network.db_entities.CommentEntityModel
import com.estatia.realestate.apps.core.network.db_names.FirestoreCollections
import com.estatia.realestate.apps.core.network.db_names.FirestoreFields
import com.estatia.realestate.apps.core.network.di.FirebaseMapper
import com.estatia.realestate.apps.core.network.interfaces.ICommentsRemoteDataSource
import com.estatia.realestate.apps.core.network.interfaces.IDatabaseErrorMapper
import com.estatia.realestate.apps.core.network.interfaces.INetworkClient
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import java.util.UUID
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/**
 * Firebase implementation of [ICommentsRemoteDataSource].
 *
 * 🏗️ OPERATIONAL CONTRACT:
 * - Responsibility: Manage comment streams and submissions.
 * - Concurrency: Thread-safe reactive observation via [callbackFlow].
 * - Resilience: Uses [RetryConfigs.COMMENTS] and a stable comment ID for retries.
 * - Safety: Derives author identity and increments the aggregate on the server.
 * - Observability: Tracks comment observation stream health and submission latency.
 */
@Utility
internal class FirestoreComments @Inject constructor(
    private val database: FirebaseFirestore,
    private val functions: FirebaseFunctions,
    private val auth: FirebaseAuth,
    private val networkClient: INetworkClient,
    private val metricsTracker: IMetricsTracker,
    @FirebaseMapper private val errorMapper: IDatabaseErrorMapper
) : ICommentsRemoteDataSource {


    override suspend fun submitComment(
        comment: CommentEntityModel
    ): AppResult<Unit> {
        val startTime = System.currentTimeMillis()
        val commentId = comment.id
            ?.takeIf(UUID_V4_PATTERN::matches)
            ?: UUID.randomUUID().toString()

        return networkClient.execute(RetryConfigs.COMMENTS) {
            val authenticatedUid = auth.currentUser?.uid
                ?: throw AuthException.UserNotAuthenticated
            if (authenticatedUid != comment.authorId) {
                throw AuthException.UserNotAuthenticated
            }

            functions
                .getHttpsCallable("submitPropertyComment")
                .call(
                    mapOf(
                        "propertyId" to comment.propertyId,
                        "commentId" to commentId,
                        "message" to comment.message
                    )
                )
                .await()

            val duration = System.currentTimeMillis() - startTime
            metricsTracker.trackDuration(
                "network.comments.submit_latency",
                duration.milliseconds
            )
            metricsTracker.incrementCounter("network.comments.submit_success")
            Unit
        }
    }


    override fun observeComments(
        propertyId: String
    ): Flow<AppResult<List<CommentEntityModel>>> =
        callbackFlow {

            val listener =
                commentsCollection(propertyId)
                    .orderBy(
                        FirestoreFields.TIMESTAMP,
                        Query.Direction.DESCENDING
                    )
                    .addSnapshotListener { snapshot, error ->

                        if (error != null) {
                            trySend(
                                AppResult.Error(
                                    errorMapper.map(error)
                                )
                            )
                            return@addSnapshotListener
                        }

                        val comments =
                            snapshot?.documents
                                ?.mapNotNull {
                                    it.toObject(CommentEntityModel::class.java)
                                }
                                ?: emptyList()

                        trySend(
                            AppResult.Success(comments)
                        )
                    }


            awaitClose {
                listener.remove()
            }
        }


    private companion object {
        val UUID_V4_PATTERN = Regex(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-" +
                "[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$"
        )
    }

    private fun commentsCollection(propertyId: String) =
        database.collection(FirestoreCollections.PROPERTIES)
            .document(propertyId)
            .collection(FirestoreCollections.SubCollections.COMMENTS)
}
