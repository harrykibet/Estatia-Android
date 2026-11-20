package com.estatia.realestate.apps

import com.estatia.realestate.apps.core.network.interfaces.IFirebaseAppCheckProxy
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import javax.inject.Inject
import com.estatia.realestate.apps.core.architecture.annotations.Logic.Utility

@Utility
internal class FirebaseAppCheckProxyImpl @Inject constructor() : IFirebaseAppCheckProxy {
    override fun initialize() {
        // Firebase emulators do not enforce App Check. Keep this opt-out limited to the
        // explicit CI emulator build so ordinary debug builds still use registered tokens.
        if (BuildConfig.FIREBASE_EMULATOR_INTEGRATION) return

        FirebaseAppCheck.getInstance()
            .installAppCheckProviderFactory(
                DebugAppCheckProviderFactory.getInstance()
            )
    }
}
