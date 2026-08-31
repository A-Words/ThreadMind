package app.threadmind.di

import android.content.Context
import androidx.work.WorkManager
import app.threadmind.work.AndroidWorkflowWorkScheduler
import app.threadmind.work.WorkflowWorkScheduler
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object WorkModule {
    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager = WorkManager.getInstance(context)

    @Provides
    @Singleton
    fun provideWorkflowWorkScheduler(workManager: WorkManager): WorkflowWorkScheduler = AndroidWorkflowWorkScheduler(workManager)
}
