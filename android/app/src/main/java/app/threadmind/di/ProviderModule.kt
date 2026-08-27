package app.threadmind.di

import app.threadmind.provider.AndroidProviderExecutor
import app.threadmind.provider.ProviderExecutor
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ProviderModule {
    @Binds
    @Singleton
    abstract fun bindProviderExecutor(impl: AndroidProviderExecutor): ProviderExecutor
}
