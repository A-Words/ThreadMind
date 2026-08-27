package app.threadmind.di

import app.threadmind.BuildConfig
import app.threadmind.auth.AuthRepository
import app.threadmind.auth.SupabaseAuthRepository
import app.threadmind.auth.UnavailableAuthRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AuthModule {
    @Provides
    @Singleton
    fun provideAuthRepository(): AuthRepository {
        if (BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_PUBLISHABLE_KEY.isBlank()) {
            return UnavailableAuthRepository(
                "尚未配置 Supabase。请在 ~/.gradle/gradle.properties 设置 THREADMIND_SUPABASE_URL 和 THREADMIND_SUPABASE_PUBLISHABLE_KEY。",
            )
        }
        val client = createSupabaseClient(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_PUBLISHABLE_KEY) {
            install(Auth)
        }
        return SupabaseAuthRepository(client)
    }
}
