package app.threadmind.di

import android.content.Context
import app.threadmind.BuildConfig
import app.threadmind.auth.AuthRepository
import app.threadmind.network.ThreadMindApi
import app.threadmind.network.ThreadMindApiFactory
import app.threadmind.network.UnavailableThreadMindApi
import app.threadmind.network.AndroidSubmissionWorkflowRepository
import app.threadmind.network.SubmissionWorkflowRepository
import dagger.hilt.android.qualifiers.ApplicationContext
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
                "尚未配置服务端地址。请在 android/local.properties 设置 THREADMIND_API_BASE_URL。",
            )
        } else {
            ThreadMindApiFactory.create(BuildConfig.API_BASE_URL, authRepository)
        }

    @Provides
    @Singleton
    fun provideSubmissionWorkflowRepository(
        @ApplicationContext context: Context,
        api: ThreadMindApi,
    ): SubmissionWorkflowRepository = AndroidSubmissionWorkflowRepository(context, api)
}
