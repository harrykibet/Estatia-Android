package com.estatia.realestate.apps.core.network.sources.firebase

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import com.estatia.realestate.apps.core.architecture.annotations.Logic.Utility
import com.estatia.realestate.apps.core.common.exceptions.AppResult
import com.estatia.realestate.apps.core.common.exceptions.AuthException
import com.estatia.realestate.apps.core.common.exceptions.DatabaseException
import com.estatia.realestate.apps.core.common.media.MediaFormat
import com.estatia.realestate.apps.core.domain.analytics.IMetricsTracker
import com.estatia.realestate.apps.core.model.property.PropertyCursor
import com.estatia.realestate.apps.core.model.property.PropertyUpdateFields
import com.estatia.realestate.apps.core.network.core.RetryConfigs
import com.estatia.realestate.apps.core.network.db_entities.PropertyContactEntity
import com.estatia.realestate.apps.core.network.db_entities.PropertyEntityModel
import com.estatia.realestate.apps.core.network.db_entities.PropertyRemotePage
import com.estatia.realestate.apps.core.network.db_names.FirestoreCollections.PROPERTIES
import com.estatia.realestate.apps.core.network.db_names.FirestoreCollections.SubCollections.CONTACT
import com.estatia.realestate.apps.core.network.db_names.FirestoreCollections.SubCollections.LIKED_PROPERTIES
import com.estatia.realestate.apps.core.network.db_names.FirestoreCollections.USERS
import com.estatia.realestate.apps.core.network.db_names.FirestoreFields
import com.estatia.realestate.apps.core.network.interfaces.INetworkClient
import com.estatia.realestate.apps.core.network.interfaces.IPropertyRemoteDatasource
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import com.google.firebase.storage.StorageException
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.tasks.await

/**
 * Firebase implementation of [IPropertyRemoteDatasource].
 *
 * 🏗️ OPERATIONAL CONTRACT:
 * - Responsibility: Low-level Firestore and Storage operations for property entities.
 * - Ownership: Property creation, update and deletion require the current Firebase Auth UID.
 * - Publication: Client-created listings begin inactive; activation remains server-owned.
 * - Storage: An owner-bound draft exists before uploads, with MIME metadata and compensating cleanup.
 * - Resilience: Multi-resource uploads use a no-retry executor and clean up paths created by the attempt.
 * - Performance: Offloads HLS generation to server-side; performs only direct VOD/Image uploads.
 * - Observability: Tracks upload and fetch latency for property management.
 */
@Utility
internal class FirestoreProperties @Inject constructor(
    private val database: FirebaseFirestore,
    private val storage: FirebaseStorage,
    private val functions: FirebaseFunctions,
    private val auth: FirebaseAuth,
    @ApplicationContext private val context: Context,
    private val networkClient: INetworkClient,
    private val metricsTracker: IMetricsTracker
) : IPropertyRemoteDatasource {

    private companion object {
        const val MAX_LEGACY_LIKE_MIGRATION_BATCHES = 10
    }

    private data class PendingMediaUpload(
        val uri: Uri,
        val path: String,
        val metadata: StorageMetadata
    )

    override suspend fun uploadProperty(
        property: PropertyEntityModel,
        contactInfo: PropertyContactEntity,
        imageUris: List<Uri>,
        videoUris: List<Uri>
    ): AppResult<String> {
        val authenticatedUid = auth.currentUser?.uid?.takeIf { it.isNotBlank() }
            ?: return AppResult.Error(AuthException.UserNotAuthenticated)
        if (!property.ownerId.isNullOrBlank() && property.ownerId != authenticatedUid) {
            return AppResult.Error(AuthException.UserNotAuthenticated)
        }

        // Generate stable identifiers and object paths before the one-shot executor.
        // Multi-resource upload is deliberately not auto-retried after compensation.
        val propertyId = property.id.ifBlank { UUID.randomUUID().toString() }
        val propertyRef = database.collection(PROPERTIES).document(propertyId)
        val contactRef = propertyRef.collection(CONTACT).document("info")
        val uploadAttemptId = UUID.randomUUID().toString()
        val imagePaths = imageUris.map {
            "$PROPERTIES/$propertyId/${MediaFormat.MEDIA_TYPE_IMAGES}/${UUID.randomUUID()}"
        }
        val videoPaths = videoUris.map {
            "$PROPERTIES/$propertyId/${MediaFormat.MEDIA_TYPE_VIDEOS}/${UUID.randomUUID()}"
        }
        val uploadedPaths = linkedSetOf<String>()
        val startTime = System.currentTimeMillis()

        return networkClient.execute(RetryConfigs.NO_RETRY) {
            val imageUploads = buildMediaUploads(
                imageUris,
                imagePaths,
                MediaFormat.IMAGE_FORMATS
            )
            val videoUploads = buildMediaUploads(
                videoUris,
                videoPaths,
                MediaFormat.VIDEO_FORMATS
            )

            // The owner-bound parent must exist before Storage uploads so rules
            // can authorize properties/{id}/images|videos by looking up ownerId.
            val draft = FirestorePropertyWriteMapper.toInactiveDraft(
                property = property,
                propertyId = propertyId,
                authenticatedOwnerId = authenticatedUid,
                uploadAttemptId = uploadAttemptId
            )
            var draftCreated = false
            try {
                // A transaction prevents a caller-supplied existing property ID
                // from overwriting somebody else's document or private contact.
                database.runTransaction { transaction ->
                    val existing = transaction.get(propertyRef)
                    if (existing.exists()) {
                        throw DatabaseException.InvalidData(
                            "The property identifier is already in use."
                        )
                    }
                    transaction.set(propertyRef, draft)
                    transaction.set(
                        contactRef,
                        mapOf(
                            "phone" to contactInfo.phone,
                            "email" to contactInfo.email
                        )
                    )
                }.await()
                draftCreated = true

                val imageUrls = uploadMedia(imageUploads, uploadedPaths)
                val videoUrls = uploadMedia(videoUploads, uploadedPaths)

                propertyRef.update(
                    FirestorePropertyWriteMapper.toMediaFinalization(
                        imageUrls = imageUrls,
                        videoUrls = videoUrls
                    )
                ).await()

                val duration = System.currentTimeMillis() - startTime
                metricsTracker.trackDuration(
                    "network.properties.upload_latency",
                    duration.milliseconds
                )
                metricsTracker.incrementCounter("network.properties.upload_success")
                propertyId
            } catch (failure: Exception) {
                // Includes a download-URL failure after a Storage object was created
                // and a Firestore finalization failure. Cleanup never masks the cause.
                withContext(NonCancellable) {
                    // Usually set after the creation transaction returns. If its
                    // acknowledgement was lost, prove ownership using the unique
                    // marker before cleaning anything. Never delete an existing ID.
                    val matchingDraft = draftCreated || try {
                        val snapshot = propertyRef.get().await()
                        snapshot.exists() &&
                            snapshot.getString("ownerId") == authenticatedUid &&
                            snapshot.getString("uploadAttemptId") == uploadAttemptId
                    } catch (_: Exception) {
                        false
                    }
                    if (matchingDraft) {
                        rollbackFailedPropertyUpload(propertyRef, contactRef, uploadedPaths)
                    }
                }
                throw failure
            }
        }
    }

    private fun buildMediaUploads(
        uris: List<Uri>,
        paths: List<String>,
        allowedFormats: Set<MediaFormat>
    ): List<PendingMediaUpload> = uris.mapIndexed { index, uri ->
        val mimeType = context.contentResolver.getType(uri)
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                MimeTypeMap.getFileExtensionFromUrl(uri.toString())
            )
            ?: throw DatabaseException.InvalidData(
                "Unable to determine the media content type."
            )
        val format = MediaFormat.fromMimeType(mimeType.lowercase())
        if (format == null || format !in allowedFormats) {
            throw DatabaseException.InvalidData(
                "Unsupported media content type."
            )
        }

        PendingMediaUpload(
            uri = uri,
            path = paths[index],
            metadata = StorageMetadata.Builder()
                .setContentType(format.mimeType)
                .build()
        )
    }

    private suspend fun uploadMedia(
        uploads: List<PendingMediaUpload>,
        uploadedPaths: MutableSet<String>
    ): List<String> = uploads.map { upload ->
        // Add the unique path before starting putFile: if the server commits the
        // object but the client loses the task result, compensation still tries it.
        uploadedPaths.add(upload.path)
        storage.reference
            .child(upload.path)
            .putFile(upload.uri, upload.metadata)
            .await()
            .storage
            .downloadUrl
            .await()
            .toString()
    }

    private suspend fun rollbackFailedPropertyUpload(
        propertyRef: com.google.firebase.firestore.DocumentReference,
        contactRef: com.google.firebase.firestore.DocumentReference,
        uploadedPaths: Set<String>
    ) {
        var storageCleanupFailed = false

        uploadedPaths.forEach { path ->
            try {
                storage.reference.child(path).delete().await()
            } catch (exception: StorageException) {
                if (exception.errorCode != StorageException.ERROR_OBJECT_NOT_FOUND) {
                    storageCleanupFailed = true
                }
            } catch (_: Exception) {
                storageCleanupFailed = true
            }
        }

        if (storageCleanupFailed) {
            // Keep the inactive owner-bound draft as the reconciliation anchor.
            // Deleting it would make a later rules-authorized cleanup impossible.
            metricsTracker.incrementCounter("network.properties.upload_cleanup_failure")
            metricsTracker.incrementCounter("network.properties.upload_failure")
            return
        }

        try {
            database.runBatch { batch ->
                batch.delete(contactRef)
                batch.delete(propertyRef)
            }.await()
        } catch (_: Exception) {
            metricsTracker.incrementCounter("network.properties.upload_cleanup_failure")
        }
        metricsTracker.incrementCounter("network.properties.upload_failure")
    }

    override suspend fun updateProperty(
        propertyId: String,
        updates: PropertyUpdateFields
    ): AppResult<Unit> {
        return networkClient.execute {
            val authenticatedUid = requireAuthenticatedUser()
            if (updates.active != null) {
                throw DatabaseException.InvalidData(
                    "Listing activation is controlled by the backend."
                )
            }

            val propertyRef = database.collection(PROPERTIES).document(propertyId)
            val snapshot = propertyRef.get().await()
            if (!snapshot.exists()) {
                throw DatabaseException.NotFound
            }
            if (snapshot.getString("ownerId") != authenticatedUid) {
                throw AuthException.UserNotAuthenticated
            }

            propertyRef.update(
                mapOf(
                    FirestoreFields.TITLE to updates.title,
                    FirestoreFields.DESCRIPTION to updates.description,
                    FirestoreFields.PRICE to updates.price,
                    FirestoreFields.DEPOSIT_AMOUNT to updates.depositAmount,
                    FirestoreFields.COUNTY to updates.county,
                    FirestoreFields.PROPERTY_TYPE to updates.propertyType,
                    FirestoreFields.BEDROOMS to updates.bedrooms,
                    FirestoreFields.BATHROOMS to updates.bathrooms,
                    FirestoreFields.AREA_SIZE to updates.areaSize,
                    FirestoreFields.AMENITIES to updates.amenities,
                    FirestoreFields.FEATURES to updates.features,
                    FirestoreFields.ADDRESS to updates.address,
                    FirestoreFields.AVAILABLE_FROM to updates.availableFrom,
                    FirestoreFields.LEASE_TERMS to updates.leaseTerms,
                    FirestoreFields.AVAILABLE to updates.available,
                    "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                ).filterValues { it != null }
            ).await()
        }
    }

    override suspend fun deleteProperty(
        propertyId: String
    ): AppResult<Unit> {
        return networkClient.execute {
            val authenticatedUid = requireAuthenticatedUser()
            val propertyRef = database.collection(PROPERTIES).document(propertyId)
            val snapshot = propertyRef.get().await()
            if (!snapshot.exists()) {
                throw DatabaseException.NotFound
            }
            if (snapshot.getString("ownerId") != authenticatedUid) {
                throw AuthException.UserNotAuthenticated
            }

            database.runBatch { batch ->
                batch.delete(propertyRef.collection(CONTACT).document("info"))
                batch.delete(propertyRef)
            }.await()
        }
    }

    private fun requireAuthenticatedUser(): String =
        auth.currentUser?.uid?.takeIf { it.isNotBlank() }
            ?: throw AuthException.UserNotAuthenticated


    override suspend fun getPropertyById(
        propertyId: String
    ): AppResult<PropertyEntityModel> {

        return networkClient.execute {

            val snapshot =
                database.collection(PROPERTIES)
                    .document(propertyId)
                    .get()
                    .await()


            if(!snapshot.exists()) {
                throw DatabaseException.NotFound
            }


            val property = snapshot.data?.let { fields ->
                FirestorePropertyMapper.toEntity(snapshot.id, fields)
            } ?: throw DatabaseException.InvalidData(
                "Unable to parse property"
            )

            // Do not read private contact data as a side effect of a public listing
            // read. Until the product defines a lead/contact entitlement, only the
            // authenticated owner may read properties/{id}/contact/info.
            val contactInfo = if (
                FirestorePropertyReadPolicy.canReadPrivateContact(
                    authenticatedUid = auth.currentUser?.uid,
                    propertyOwnerId = property.ownerId
                )
            ) {
                database.collection(PROPERTIES)
                    .document(propertyId)
                    .collection(CONTACT)
                    .document("info")
                    .get()
                    .await()
                    .toObject(PropertyContactEntity::class.java)
            } else {
                null
            }

            property.copy(contact = contactInfo)
        }
    }

    override suspend fun fetchLikedProperties(
        userId: String
    ): AppResult<List<PropertyEntityModel>> {

        return networkClient.execute(RetryConfigs.PROPERTY_FEED) {
            requireAuthenticatedUser(userId)

            var remainingLegacyLikes = 0
            for (attempt in 0 until MAX_LEGACY_LIKE_MIGRATION_BATCHES) {
                val migration = functions
                    .getHttpsCallable("migrateLegacyPropertyLikes")
                    .call(emptyMap<String, Any>())
                    .await()
                val data = migration.data as? Map<*, *>
                    ?: throw DatabaseException.InvalidData(
                        "Legacy favorite migration returned an invalid response."
                    )
                remainingLegacyLikes = (data["remaining"] as? Number)?.toInt()
                    ?: throw DatabaseException.InvalidData(
                        "Legacy favorite migration omitted its remaining count."
                    )
                if (remainingLegacyLikes == 0) {
                    break
                }
            }

            if (remainingLegacyLikes > 0) {
                throw DatabaseException.InvalidData(
                    "Legacy favorite migration remains incomplete after " +
                        "$MAX_LEGACY_LIKE_MIGRATION_BATCHES batches."
                )
            }

            val likedPropertyIds = database.collection(USERS)
                .document(userId)
                .collection(LIKED_PROPERTIES)
                .get()
                .await()
                .documents.map { it.id }

            if (likedPropertyIds.isNotEmpty()) {
                // 🛡️ Firestore whereIn limit is 30 values.
                // Chunk the IDs to avoid "too many values in whereIn" error.
                likedPropertyIds.chunked(30).flatMap { chunk ->
                    database.collection(PROPERTIES)
                        .whereEqualTo(FirestoreFields.ACTIVE, true)
                        .whereIn(FieldPath.documentId(), chunk)
                        .get()
                        .await()
                        .documents.mapNotNull { document ->
                            document.data?.let { fields ->
                                FirestorePropertyMapper.toEntity(document.id, fields)
                            }
                        }
                }
            } else {
                emptyList()
            }
        }
    }

    override suspend fun likeProperty(
        userId: String,
        propertyId: String
    ): AppResult<Unit> {


        return networkClient.execute(RetryConfigs.PROPERTY_FEED) {
            requireAuthenticatedUser(userId)
            functions
                .getHttpsCallable("setPropertyLike")
                .call(
                    mapOf(
                        "propertyId" to propertyId,
                        "liked" to true
                    )
                )
                .await()
            Unit
        }
    }

    override suspend fun unlikeProperty(
        userId: String,
        propertyId: String
    ): AppResult<Unit> {


        return networkClient.execute(RetryConfigs.PROPERTY_FEED) {
            requireAuthenticatedUser(userId)
            functions
                .getHttpsCallable("setPropertyLike")
                .call(
                    mapOf(
                        "propertyId" to propertyId,
                        "liked" to false
                    )
                )
                .await()
            Unit
        }
    }

    /**
     * Verifies the requested user ID matches the Firebase session.
     *
     * The backend independently derives the UID from the callable auth token.
     *
     * @param userId User ID supplied by the repository layer.
     */
    private fun requireAuthenticatedUser(userId: String) {
        val authenticatedUid = auth.currentUser?.uid
            ?: throw AuthException.UserNotAuthenticated
        if (authenticatedUid != userId) {
            throw AuthException.UserNotAuthenticated
        }
    }

    override suspend fun recordView(propertyId: String): AppResult<Unit> {
        val eventId = UUID.randomUUID().toString()
        return networkClient.execute(RetryConfigs.ANALYTICS) {
            requireAuthenticatedSession()
            functions
                .getHttpsCallable("recordPropertyEngagement")
                .call(
                    mapOf(
                        "propertyId" to propertyId,
                        "eventId" to eventId,
                        "eventType" to "view"
                    )
                )
                .await()
            Unit
        }
    }

    override suspend fun recordShare(propertyId: String): AppResult<Unit> {
        val eventId = UUID.randomUUID().toString()
        return networkClient.execute(RetryConfigs.ANALYTICS) {
            requireAuthenticatedSession()
            functions
                .getHttpsCallable("recordPropertyEngagement")
                .call(
                    mapOf(
                        "propertyId" to propertyId,
                        "eventId" to eventId,
                        "eventType" to "share"
                    )
                )
                .await()
            Unit
        }
    }

    /**
     * Requires a signed-in user before recording a server-owned event.
     */
    private fun requireAuthenticatedSession() {
        if (auth.currentUser?.uid.isNullOrBlank()) {
            throw AuthException.UserNotAuthenticated
        }
    }

    override suspend fun fetchPropertiesPaginated(
        userId: String?,
        cursor: PropertyCursor?,
        pageSize: Int
    ): AppResult<PropertyRemotePage> {


        return networkClient.execute(RetryConfigs.PROPERTY_FEED) {


            val query =
                database.collection(PROPERTIES)
                    .whereEqualTo(FirestoreFields.ACTIVE, true)
                    .orderBy(
                        FirestoreFields.CREATED_AT,
                        Query.Direction.DESCENDING
                    )
                    .let {

                        if(cursor != null)
                            it.startAfter(
                                FirestorePropertyMapper.toFirestoreCursorValue(cursor),
                                cursor.documentId
                            )
                        else
                            it

                    }
                    .limit(pageSize.toLong())


            val snapshot =
                query.get().await()


            val properties =
                snapshot.documents.mapNotNull { document ->
                    document.data?.let { fields ->
                        FirestorePropertyMapper.toEntity(document.id, fields)
                    }
                }


            val last =
                snapshot.documents.lastOrNull()


            PropertyRemotePage(
                properties = properties,
                cursor =
                    last?.let {
                        FirestorePropertyMapper.toCursor(
                            documentId = it.id,
                            rawCreatedAt = it.get(FirestoreFields.CREATED_AT),
                        )
                    }
            )
        }
    }
}
