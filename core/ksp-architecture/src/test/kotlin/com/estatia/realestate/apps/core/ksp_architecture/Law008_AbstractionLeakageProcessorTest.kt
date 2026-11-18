package com.estatia.realestate.apps.core.ksp_architecture

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCompilerApi::class)
class Law008_AbstractionLeakageProcessorTest {

    @Test
    fun `LAW-008 Abstraction leakage fails compilation`() {
        val source = SourceFile.kotlin(
            "TestRepository.kt",
            """
            package com.estatia.realestate.apps.core.data.repositories
            import com.estatia.realestate.apps.core.architecture.annotations.Identity.Repository
            import com.estatia.realestate.apps.core.common.exceptions.AppResult
            import com.google.firebase.auth.FirebaseUser
            
            interface ITestRepository
            
            @Repository
            class TestRepository : ITestRepository {
                fun getUser(): AppResult<FirebaseUser> = TODO()
            }
            """.trimIndent()
        )

        val result = KspTestUtils.compile(
            KspTestUtils.annotationsSource, 
            KspTestUtils.resultSource,
            KspTestUtils.firebaseStub,
            source,
            providers = listOf(Law008_AbstractionLeakageProcessorProvider())
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.messages.contains("LAW-008"))
    }

    @Test
    fun `LAW-008 ignores public members of an internal service implementation`() {
        val source = SourceFile.kotlin(
            "InternalService.kt",
            """
            package com.estatia.realestate.apps.core.network
            import com.estatia.realestate.apps.core.architecture.annotations.Identity.Service
            import com.estatia.realestate.apps.core.common.exceptions.AppResult
            import com.google.firebase.auth.FirebaseUser

            @Service
            internal class InternalService {
                fun currentUser(): AppResult<FirebaseUser> = TODO()
            }
            """.trimIndent()
        )

        val result = KspTestUtils.compile(
            KspTestUtils.annotationsSource,
            KspTestUtils.resultSource,
            KspTestUtils.firebaseStub,
            source,
            providers = listOf(Law008_AbstractionLeakageProcessorProvider())
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
        assertFalse(result.messages.contains("LAW-008"))
    }

    @Test
    fun `LAW-008 ignores public nested service inside an internal enclosing class`() {
        val source = SourceFile.kotlin(
            "NestedService.kt",
            """
            package com.estatia.realestate.apps.core.network
            import com.estatia.realestate.apps.core.architecture.annotations.Identity.Service
            import com.estatia.realestate.apps.core.common.exceptions.AppResult
            import com.google.firebase.auth.FirebaseUser

            internal class InternalContainer {
                @Service
                class NestedService {
                    fun currentUser(): AppResult<FirebaseUser> = TODO()
                }
            }
            """.trimIndent()
        )

        val result = KspTestUtils.compile(
            KspTestUtils.annotationsSource,
            KspTestUtils.resultSource,
            KspTestUtils.firebaseStub,
            source,
            providers = listOf(Law008_AbstractionLeakageProcessorProvider())
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode)
        assertFalse(result.messages.contains("LAW-008"))
    }

    @Test
    fun `LAW-008 still rejects infrastructure leakage from a public service API`() {
        val source = SourceFile.kotlin(
            "PublicService.kt",
            """
            package com.estatia.realestate.apps.core.network
            import com.estatia.realestate.apps.core.architecture.annotations.Identity.Service
            import com.estatia.realestate.apps.core.common.exceptions.AppResult
            import com.google.firebase.auth.FirebaseUser

            @Service
            class PublicService {
                fun currentUser(): AppResult<FirebaseUser> = TODO()
            }
            """.trimIndent()
        )

        val result = KspTestUtils.compile(
            KspTestUtils.annotationsSource,
            KspTestUtils.resultSource,
            KspTestUtils.firebaseStub,
            source,
            providers = listOf(Law008_AbstractionLeakageProcessorProvider())
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(result.messages.contains("LAW-008"))
        assertTrue(result.messages.contains("com.google.firebase.auth.FirebaseUser"))
    }

}
