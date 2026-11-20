package com.estatia.realestate.apps

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.estatia.realestate.apps.core.domain.config.IConfigProvider
import com.estatia.realestate.apps.core.domain.analytics.ICrashReporter
import com.estatia.realestate.apps.core.common.interfaces.IBackendInitializer
import com.estatia.realestate.apps.core.architecture.annotations.Identity.AppEntryPoint
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security
import javax.inject.Inject

@AppEntryPoint
@HiltAndroidApp
class EstatiaApplication : Application(), Configuration.Provider  {

    @Inject
    lateinit var config: IConfigProvider

    @Inject
    lateinit var crashReporter: ICrashReporter

    @Inject
    lateinit var backendInitializers: Set<@JvmSuppressWildcards IBackendInitializer>

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() {
            if (!::workerFactory.isInitialized) {
                return Configuration.Builder().build()
            }
            return Configuration.Builder()
                .setWorkerFactory(workerFactory)
                .build()
        }

    override fun onCreate() {
        super.onCreate()
        initializeBouncyCastle()
        configureGlobalExceptionHandler()
        
        // Config-backed singletons are created while MainActivity is injected.
        // Load the bundled snapshot before Application.onCreate returns. ConfigProvider
        // performs asset IO on its injected IO dispatcher and starts remote refresh separately.
        // Complete Firebase/App Check setup before the first Activity or ViewModel
        // can observe authentication or issue Firestore requests.
        runBlocking {
            config.initialize()
            initializeBackends()
        }
    }

    private suspend fun initializeBackends() {
        withContext(Dispatchers.Main) {
            backendInitializers.forEach { it.initialize() }
        }
    }


    private fun initializeBouncyCastle() {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }


    private fun configureGlobalExceptionHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            handleUncaughtException(thread, throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }

        CoroutineExceptionHandler { _, throwable ->
            handleUncaughtException(Thread.currentThread(), throwable)
        }
    }


    private fun handleUncaughtException(thread: Thread, throwable: Throwable) {
        val errorMessage = "Uncaught exception in thread '${thread.name}': ${throwable.localizedMessage}"

        crashReporter.log("[CRITICAL] $errorMessage")
        crashReporter.recordException(throwable)
    }
}
