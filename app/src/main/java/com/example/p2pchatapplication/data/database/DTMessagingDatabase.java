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
        entities = {MessageEntity.class, FriendEntity.class},
        version = 2,
        exportSchema = true
)
public abstract class DTMessagingDatabase extends RoomDatabase {

    // Abstract method to get DAO
    public abstract MessageDao messageDao();
    public abstract FriendDao friendDao();

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
                            .addMigrations(MIGRATION_1_2)  // Add migration for friends table
                            .fallbackToDestructiveMigration() // For development
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

            databaseWriteExecutor.execute(() -> {
                // Database created
                android.util.Log.d("DTMessagingDB", "Database created with friends support");
            });
        }

        @Override
        public void onOpen(SupportSQLiteDatabase db) {
            super.onOpen(db);

            // Clean up expired messages and set all friends offline on app start
            databaseWriteExecutor.execute(() -> {
                MessageDao messageDao = INSTANCE.messageDao();
                FriendDao friendDao = INSTANCE.friendDao();

                long currentTime = System.currentTimeMillis();
                int deletedCount = messageDao.deleteExpiredMessages(currentTime);
                if (deletedCount > 0) {
                    android.util.Log.d("DTMessagingDB",
                            "Cleaned up " + deletedCount + " expired messages");
                }

                // Set all friends offline (they'll be marked online when they connect)
                friendDao.setAllOffline();
                android.util.Log.d("DTMessagingDB", "Set all friends offline on app start");
            });
        }
    };

    /**
     * Migration from version 1 to 2 (adds friends table)
     */
    static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            // Create friends table
            database.execSQL(
                    "CREATE TABLE IF NOT EXISTS friends (" +
                            "user_id TEXT PRIMARY KEY NOT NULL, " +
                            "nickname TEXT, " +
                            "endpoint_id TEXT, " +
                            "last_seen INTEGER NOT NULL, " +
                            "added_date INTEGER NOT NULL, " +
                            "is_online INTEGER NOT NULL DEFAULT 0, " +
                            "total_messages INTEGER NOT NULL DEFAULT 0, " +
                            "is_favorite INTEGER NOT NULL DEFAULT 0)"
            );
            android.util.Log.d("DTMessagingDB", "Migrated database to version 2 (added friends)");
        }
    };

}