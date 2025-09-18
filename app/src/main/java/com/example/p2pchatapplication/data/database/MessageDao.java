package com.example.p2pchatapplication.data.database;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

/**
 * Data Access Object for Message operations.
 * Provides methods for CRUD operations on messages table.
 */
@Dao
public interface MessageDao {

    /**
     * Insert a new message. Replace if conflict occurs.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertMessage(MessageEntity message);

    /**
     * Insert multiple messages in a single transaction
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertMessages(List<MessageEntity> messages);

    /**
     * Update an existing message
     */
    @Update
    void updateMessage(MessageEntity message);

    /**
     * Delete a specific message
     */
    @Delete
    void deleteMessage(MessageEntity message);

    /**
     * Get all messages ordered by timestamp (oldest first for chronological chat).
     * LiveData for automatic UI updates.
     */
    @Query("SELECT * FROM messages ORDER BY timestamp ASC")
    LiveData<List<MessageEntity>> getAllMessages();

    /**
     * Get messages for a specific conversation between two users
     */
    @Query("SELECT * FROM messages WHERE " +
            "(sender_id = :userId1 AND recipient_id = :userId2) OR " +
            "(sender_id = :userId2 AND recipient_id = :userId1) " +
            "ORDER BY timestamp ASC")
    LiveData<List<MessageEntity>> getConversation(String userId1, String userId2);

    /**
     * Get all pending messages that need to be forwarded
     */
    @Query("SELECT * FROM messages WHERE status = :status ORDER BY timestamp ASC")
    List<MessageEntity> getPendingMessages(int status);

    /**
     * Get messages that need to be forwarded (not sent by this device and not delivered)
     */
    @Query("SELECT * FROM messages WHERE is_outgoing = 0 AND status != :deliveredStatus " +
            "AND ttl > :currentTime ORDER BY timestamp ASC")
    List<MessageEntity> getMessagesToForward(int deliveredStatus, long currentTime);

    /**
     * Get a specific message by ID
     */
    @Query("SELECT * FROM messages WHERE message_id = :messageId LIMIT 1")
    MessageEntity getMessageById(String messageId);

    /**
     * Update message status
     */
    @Query("UPDATE messages SET status = :status WHERE message_id = :messageId")
    void updateMessageStatus(String messageId, int status);

    /**
     * Delete expired messages (TTL exceeded)
     */
    @Query("DELETE FROM messages WHERE ttl < :currentTime")
    int deleteExpiredMessages(long currentTime);

    /**
     * Get message count for statistics
     */
    @Query("SELECT COUNT(*) FROM messages")
    LiveData<Integer> getMessageCount();

    /**
     * Get pending message count
     */
    @Query("SELECT COUNT(*) FROM messages WHERE status = :pendingStatus")
    LiveData<Integer> getPendingMessageCount(int pendingStatus);

    /**
     * Check if a message already exists (to prevent duplicates)
     */
    @Query("SELECT COUNT(*) FROM messages WHERE message_id = :messageId")
    int messageExists(String messageId);

    /**
     * Get messages by recipient for store-and-forward delivery
     */
    @Query("SELECT * FROM messages WHERE recipient_id = :recipientId AND status != :deliveredStatus")
    List<MessageEntity> getMessagesForRecipient(String recipientId, int deliveredStatus);
}