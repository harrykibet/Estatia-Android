package com.estatia.realestate.apps.core.analytics.di

import com.estatia.realestate.apps.core.analytics.AnalyticsHelper
import com.estatia.realestate.apps.core.analytics.IAnalyticsHelper
import com.estatia.realestate.apps.core.analytics.MeterRegistryFactory
import com.estatia.realestate.apps.core.architecture.annotations.Logic.Utility
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import io.micrometer.core.instrument.logging.LoggingMeterRegistry
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
@Utility
abstract class DemoAnalyticsModule {
    @Binds
    @Singleton
    abstract fun bindsAnalyticsHelper(analyticsHelperImpl: AnalyticsHelper): IAnalyticsHelper

    companion object {
        @Provides
        @IntoSet
        fun provideLoggingMeterRegistryFactory(): MeterRegistryFactory =
            MeterRegistryFactory { LoggingMeterRegistry() }
    }
}
