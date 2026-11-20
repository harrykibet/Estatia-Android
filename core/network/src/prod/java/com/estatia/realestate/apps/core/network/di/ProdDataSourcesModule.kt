package com.estatia.realestate.apps.core.network.di

import com.estatia.realestate.apps.core.common.interfaces.IBackendInitializer
import com.estatia.realestate.apps.core.domain.analytics.ICrashReporter
import com.estatia.realestate.apps.core.network.interfaces.IAnalyticsRemoteDataSource
import com.estatia.realestate.apps.core.network.interfaces.IAuthRemoteDataSource
import com.estatia.realestate.apps.core.network.interfaces.ICommentsRemoteDataSource
import com.estatia.realestate.apps.core.network.interfaces.IConfigRemoteDataSource
import com.estatia.realestate.apps.core.network.interfaces.IPaymentsRemoteDataSource
import com.estatia.realestate.apps.core.network.interfaces.IPropertyRemoteDatasource
import com.estatia.realestate.apps.core.network.interfaces.ISearchRemoteDataSource
import com.estatia.realestate.apps.core.network.interfaces.ISecretRemoteDataSource
import com.estatia.realestate.apps.core.network.interfaces.IUserRemoteDataSource
import com.estatia.realestate.apps.core.network.sources.SecretRemoteDataSource
import com.estatia.realestate.apps.core.network.sources.firebase.FirebaseAuthService
import com.estatia.realestate.apps.core.network.sources.firebase.FirebaseBackendInitializer
import com.estatia.realestate.apps.core.network.sources.firebase.FirebaseConfig
import com.estatia.realestate.apps.core.network.sources.firebase.FirebaseCrashReporter
import com.estatia.realestate.apps.core.network.sources.firebase.FirebasePaymentsRemoteDataSource
import com.estatia.realestate.apps.core.network.sources.firebase.FirestoreAnalytics
import com.estatia.realestate.apps.core.network.sources.firebase.FirestoreComments
import com.estatia.realestate.apps.core.network.sources.firebase.FirestoreProperties
import com.estatia.realestate.apps.core.network.sources.firebase.FirestoreSearch
import com.estatia.realestate.apps.core.network.sources.firebase.FirestoreUsers
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton
import com.estatia.realestate.apps.core.architecture.annotations.Logic.Utility

/**
 * Production data sources use Firebase. AWS implementations remain available as
 * legacy adapters, but no AWS initializer or AWS remote data source is bound into
 * the production dependency graph.
 *
 * Firebase Storage is used by media operations. The authenticated Home, Search,
 * Property Details, and navigation journeys do not invoke media uploads.
 */
@Module
@InstallIn(SingletonComponent::class)
@Utility
internal abstract class ProdDataSourcesModule {

    @Binds
    @IntoSet
    abstract fun bindFirebaseInitializer(initializer: FirebaseBackendInitializer): IBackendInitializer

    @Binds
    @Singleton
    abstract fun bindSearchRemoteSource(dataSource: FirestoreSearch): ISearchRemoteDataSource

    @Binds
    @Singleton
    abstract fun bindPropertyRemoteSource(dataSource: FirestoreProperties): IPropertyRemoteDatasource

    @Binds
    @Singleton
    abstract fun bindAuthRemoteSource(dataSource: FirebaseAuthService): IAuthRemoteDataSource

    @Binds
    @Singleton
    abstract fun bindCommentsRemoteSource(dataSource: FirestoreComments): ICommentsRemoteDataSource

    @Binds
    @Singleton
    abstract fun bindAnalyticsRemoteSource(dataSource: FirestoreAnalytics): IAnalyticsRemoteDataSource

    @Binds
    @Singleton
    abstract fun bindUserRemoteDataSource(dataSource: FirestoreUsers): IUserRemoteDataSource

    @Binds
    @Singleton
    abstract fun bindConfigRemoteDataSource(dataSource: FirebaseConfig): IConfigRemoteDataSource

    @Binds
    @Singleton
    abstract fun bindCrashReporter(reporter: FirebaseCrashReporter): ICrashReporter

    @Binds
    @Singleton
    abstract fun bindPaymentsRemoteSource(dataSource: FirebasePaymentsRemoteDataSource): IPaymentsRemoteDataSource

    @Binds
    @Singleton
    abstract fun bindSecretRemoteSource(dataSource: SecretRemoteDataSource): ISecretRemoteDataSource
}
