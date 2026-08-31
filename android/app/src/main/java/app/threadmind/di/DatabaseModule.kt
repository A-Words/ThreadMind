package app.threadmind.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
        Room.databaseBuilder(context, ThreadMindDatabase::class.java, "threadmind.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()

    @Provides
    fun provideWorkflowDao(database: ThreadMindDatabase): WorkflowDao = database.workflowDao()

    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE action_card_cache ADD COLUMN providerReviewedVersion INTEGER")
        }
    }

    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE pending_submissions ADD COLUMN extractionJson TEXT")
        }
    }
}
