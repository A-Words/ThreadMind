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
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
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

    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""CREATE TABLE pending_submissions_new (
                accountId TEXT NOT NULL, id TEXT NOT NULL, localImagePath TEXT,
                imageContentType TEXT NOT NULL, source TEXT NOT NULL, supplementalText TEXT NOT NULL,
                status TEXT NOT NULL, failureCode TEXT, extractionJson TEXT,
                createdAtEpochMillis INTEGER NOT NULL, updatedAtEpochMillis INTEGER NOT NULL,
                PRIMARY KEY(accountId, id))""")
            db.execSQL("INSERT INTO pending_submissions_new SELECT accountId,id,localImagePath,imageContentType,source,supplementalText,status,failureCode,extractionJson,createdAtEpochMillis,updatedAtEpochMillis FROM pending_submissions")
            db.execSQL("DROP TABLE pending_submissions")
            db.execSQL("ALTER TABLE pending_submissions_new RENAME TO pending_submissions")
            db.execSQL("CREATE INDEX index_pending_submissions_accountId_status ON pending_submissions(accountId,status)")
        }
    }
}
