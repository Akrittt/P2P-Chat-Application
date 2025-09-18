package com.example.p2pchatapplication.data.database;

import android.content.Context;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Room Database class for DT-Messaging app.
 * Manages local storage for messages and supports store-and-forward functionality.
 */
@Database(
        entities = {MessageEntity.class},
        version = 1,
        exportSchema = true
)
public abstract class DTMessagingDatabase extends RoomDatabase {

    // Abstract method to get DAO
    public abstract MessageDao messageDao();

    // Singleton instance
    private static volatile DTMessagingDatabase INSTANCE;

    // Thread pool for database operations
    private static final int NUMBER_OF_THREADS = 4;
    public static final ExecutorService databaseWriteExecutor =
            Executors.newFixedThreadPool(NUMBER_OF_THREADS);

    /**
     * Get database instance using singleton pattern
     */
    public static DTMessagingDatabase getDatabase(final Context context) {
        if (INSTANCE == null) {
            synchronized (DTMessagingDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                                    context.getApplicationContext(),
                                    DTMessagingDatabase.class,
                                    "dt_messaging_database"
                            )
                            .addCallback(roomDatabaseCallback)
                            .fallbackToDestructiveMigration() // For development only
                            .build();
                }
            }
        }
        return INSTANCE;
    }

    /**
     * Callback for database creation/opening
     */
    private static RoomDatabase.Callback roomDatabaseCallback = new RoomDatabase.Callback() {
        @Override
        public void onCreate(SupportSQLiteDatabase db) {
            super.onCreate(db);

            // Perform any initial setup here if needed
            databaseWriteExecutor.execute(() -> {
                // Database created - perform initialization if needed
                // For example, you could insert default data here
            });
        }

        @Override
        public void onOpen(SupportSQLiteDatabase db) {
            super.onOpen(db);

            // Clean up expired messages on database open
            databaseWriteExecutor.execute(() -> {
                MessageDao dao = INSTANCE.messageDao();
                long currentTime = System.currentTimeMillis();
                int deletedCount = dao.deleteExpiredMessages(currentTime);
                if (deletedCount > 0) {
                    android.util.Log.d("DTMessagingDB",
                            "Cleaned up " + deletedCount + " expired messages");
                }
            });
        }
    };

    // Future migration example (when you upgrade database schema)
    static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            // Add new column example:
            // database.execSQL("ALTER TABLE messages ADD COLUMN new_column TEXT");
        }
    };
}