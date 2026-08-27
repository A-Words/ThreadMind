package app.threadmind.di

import app.threadmind.BuildConfig
import app.threadmind.auth.AuthRepository
import app.threadmind.network.ThreadMindApi
import app.threadmind.network.ThreadMindApiFactory
import app.threadmind.network.UnavailableThreadMindApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun provideThreadMindApi(authRepository: AuthRepository): ThreadMindApi =
        if (BuildConfig.API_BASE_URL.isBlank()) {
            UnavailableThreadMindApi(
                "尚未配置服务端地址。请在 ~/.gradle/gradle.properties 设置 THREADMIND_API_BASE_URL。",
            )
        } else {
            ThreadMindApiFactory.create(BuildConfig.API_BASE_URL, authRepository)
        }
}
