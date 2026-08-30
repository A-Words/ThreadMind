package app.threadmind.di

import android.content.Context
import androidx.room.Room
import app.threadmind.data.local.ThreadMindDatabase
import app.threadmind.data.local.WorkflowDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): ThreadMindDatabase =
        Room.databaseBuilder(context, ThreadMindDatabase::class.java, "threadmind.db").build()

    @Provides
    fun provideWorkflowDao(database: ThreadMindDatabase): WorkflowDao = database.workflowDao()
}
