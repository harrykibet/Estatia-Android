package com.estatia.realestate.apps

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies Firebase Auth, Firestore query, and Storage upload/download SDK paths
 * against the local Firebase Emulator Suite. No live Firebase data is changed.
 */
@RunWith(AndroidJUnit4::class)
class FirebaseEmulatorIntegrationTest {

    @Test(timeout = TEST_TIMEOUT_MILLIS)
    fun authenticatedFirestoreQueryAndStorageUploadDownloadRoundTrip() {
        assumeTrue(
            "Requires the Firebase Emulator Integration workflow",
            BuildConfig.FIREBASE_EMULATOR_INTEGRATION,
        )

        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val firebaseApp = FirebaseApp.getApps(targetContext).firstOrNull()
            ?: checkNotNull(FirebaseApp.initializeApp(targetContext)) {
                "The Prod Firebase configuration did not initialize a FirebaseApp."
            }

        val auth = FirebaseAuth.getInstance(firebaseApp).apply {
            useEmulator(EMULATOR_HOST, AUTH_EMULATOR_PORT)
        }
        val firestore = FirebaseFirestore.getInstance(firebaseApp).apply {
            useEmulator(EMULATOR_HOST, FIRESTORE_EMULATOR_PORT)
        }
        val storage = FirebaseStorage.getInstance(firebaseApp).apply {
            useEmulator(EMULATOR_HOST, STORAGE_EMULATOR_PORT)
        }

        val suffix = UUID.randomUUID().toString()
        val email = "firebase-emulator-$suffix@example.test"
        val password = "CiOnly-$suffix-Aa9!"
        val payload = "Estatia Firebase Storage emulator integration $suffix".toByteArray()

        val user = checkNotNull(
            await(
                auth.createUserWithEmailAndPassword(email, password),
                "Auth emulator user creation",
            ).user,
        ) {
            "Firebase Auth Emulator did not return the created test user."
        }
        val userDocument = firestore.collection(CI_COLLECTION).document(user.uid)
        val objectPath = CI_STORAGE_PREFIX + "/" + user.uid + "/round-trip.bin"
        val objectReference = storage.reference.child(objectPath)

        try {
            await(
                userDocument.set(
                    mapOf("uid" to user.uid, "email" to email, "probe" to suffix),
                ),
                "Firestore emulator document write",
            )

            val query = await(
                firestore.collection(CI_COLLECTION)
                    .whereEqualTo("uid", user.uid)
                    .whereEqualTo("probe", suffix)
                    .get(),
                "Authenticated Firestore emulator query",
            )
            assertEquals("Firestore should return exactly one authenticated probe document.", 1, query.size())
            assertEquals(user.uid, query.documents.single().getString("uid"))

            await(objectReference.putBytes(payload), "Firebase Storage emulator upload")
            val metadata = await(objectReference.metadata, "Firebase Storage emulator metadata retrieval")
            assertEquals(payload.size.toLong(), metadata.sizeBytes)
            assertArrayEquals(
                payload,
                await(objectReference.getBytes(MAX_DOWNLOAD_BYTES), "Firebase Storage emulator download"),
            )
        } finally {
            runCatching { await(objectReference.delete(), "Firebase Storage emulator cleanup", CLEANUP_TIMEOUT_SECONDS) }
            runCatching { await(userDocument.delete(), "Firestore emulator cleanup", CLEANUP_TIMEOUT_SECONDS) }
            runCatching { await(user.delete(), "Auth emulator cleanup", CLEANUP_TIMEOUT_SECONDS) }
            auth.signOut()
        }
    }

    private companion object {
        const val TEST_TIMEOUT_MILLIS = 360_000L
        const val OPERATION_TIMEOUT_SECONDS = 30L
        const val CLEANUP_TIMEOUT_SECONDS = 10L

        fun <T> await(
            task: Task<T>,
            operation: String,
            timeoutSeconds: Long = OPERATION_TIMEOUT_SECONDS,
        ): T = try {
            Tasks.await(task, timeoutSeconds, TimeUnit.SECONDS)
        } catch (timeout: TimeoutException) {
            throw AssertionError(
                "Timed out after ${timeoutSeconds}s waiting for $operation.",
                timeout,
            )
        }

        const val EMULATOR_HOST = "10.0.2.2"
        const val AUTH_EMULATOR_PORT = 9099
        const val FIRESTORE_EMULATOR_PORT = 8080
        const val STORAGE_EMULATOR_PORT = 9199
        const val CI_COLLECTION = "ci_backend_probe"
        const val CI_STORAGE_PREFIX = "ci_backend_probe"
        const val MAX_DOWNLOAD_BYTES = 64 * 1024L
    }
}
