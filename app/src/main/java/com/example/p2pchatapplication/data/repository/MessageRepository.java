package com.example.p2pchatapplication.data.repository;

import android.app.Application;
import androidx.lifecycle.LiveData;


import com.example.p2pchatapplication.data.database.DTMessagingDatabase;
import com.example.p2pchatapplication.data.database.MessageDao;
import com.example.p2pchatapplication.data.database.MessageEntity;
import com.example.p2pchatapplication.utils.LogUtil;


import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

/**
 * Repository class that provides a clean API for accessing message data.
 * Implements the Repository pattern to abstract data sources.
 */
public class MessageRepository {


    private MessageDao messageDao;
    private LiveData<List<MessageEntity>> allMessages;
    private LiveData<Integer> messageCount;

    private static final String TAG = "MessageRepository";

    public MessageRepository(Application application) {
        DTMessagingDatabase db = DTMessagingDatabase.getDatabase(application);
        messageDao = db.messageDao();
        allMessages = messageDao.getAllMessages();
        messageCount = messageDao.getMessageCount();
    }

    /**
     * Get all messages as LiveData for UI observation
     */
    public LiveData<List<MessageEntity>> getAllMessages() {
        return allMessages;
    }

    /**
     * Get conversation between two users
     */
    public LiveData<List<MessageEntity>> getConversation(String userId1, String userId2) {
        return messageDao.getConversation(userId1, userId2);
    }

    /**
     * Get total message count
     */
    public LiveData<Integer> getMessageCount() {
        return messageCount;
    }

    /**
     * Get pending message count
     */
    public LiveData<Integer> getPendingMessageCount() {
        return messageDao.getPendingMessageCount(MessageEntity.STATUS_PENDING);
    }

    /**
     * Insert a new message asynchronously
     */
    public void insertMessage(MessageEntity message) {
        DTMessagingDatabase.databaseWriteExecutor.execute(() -> {
            try {
                messageDao.insertMessage(message);
                LogUtil.d(TAG, "Inserted message: " + message.messageId);
            } catch (Exception e) {
                LogUtil.e(TAG, "Failed to insert message: " + e.getMessage());
            }
        });
    }

    /**
     * Insert multiple messages (useful for received batch)
     */
    public void insertMessages(List<MessageEntity> messages) {
        DTMessagingDatabase.databaseWriteExecutor.execute(() -> {
            try {
                messageDao.insertMessages(messages);
                LogUtil.d(TAG, "Inserted " + messages.size() + " messages");
            } catch (Exception e) {
                LogUtil.e(TAG, "Failed to insert messages: " + e.getMessage());
            }
        });
    }

    /**
     * Update message status
     */
    public void updateMessageStatus(String messageId, int status) {
        DTMessagingDatabase.databaseWriteExecutor.execute(() -> {
            try {
                messageDao.updateMessageStatus(messageId, status);
                LogUtil.d(TAG, "Updated message " + messageId + " status to " + status);
            } catch (Exception e) {
                LogUtil.e(TAG, "Failed to update message status: " + e.getMessage());
            }
        });
    }

    /**
     * Get pending messages synchronously (for background processing)
     */
    public CompletableFuture<List<MessageEntity>> getPendingMessages() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // The DAO method is now the supplier for the CompletableFuture
                return messageDao.getPendingMessages(MessageEntity.STATUS_PENDING);
            } catch (Exception e) {
                LogUtil.e(TAG, "Failed to get pending messages: " + e.getMessage());
                return null;
            }
        }, DTMessagingDatabase.databaseWriteExecutor); // Pass the executor to run the task
    }

    /**
     * Get messages that need to be forwarded
     */
    public CompletableFuture<List<MessageEntity>> getMessagesToForward() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                long currentTime = System.currentTimeMillis();
                return messageDao.getMessagesToForward(
                        MessageEntity.STATUS_DELIVERED,
                        currentTime
                );
            } catch (Exception e) {
                LogUtil.e(TAG, "Failed to get messages to forward: " + e.getMessage());
                return null;
            }
        }, DTMessagingDatabase.databaseWriteExecutor); // Pass the executor to run the task
    }

    /**
     * Get messages for a specific recipient
     */
    public Future<List<MessageEntity>> getMessagesForRecipient(String recipientId) {
        return DTMessagingDatabase.databaseWriteExecutor.submit(() -> {
            try {
                return messageDao.getMessagesForRecipient(
                        recipientId,
                        MessageEntity.STATUS_DELIVERED
                );
            } catch (Exception e) {
                LogUtil.e(TAG, "Failed to get messages for recipient: " + e.getMessage());
                return null;
            }
        });
    }

    /**
     * Check if message exists (prevent duplicates)
     */
    public Future<Boolean> messageExists(String messageId) {
        return DTMessagingDatabase.databaseWriteExecutor.submit(() -> {
            try {
                return messageDao.messageExists(messageId) > 0;
            } catch (Exception e) {
                LogUtil.e(TAG, "Failed to check message existence: " + e.getMessage());
                return false;
            }
        });
    }

    /**
     * Delete expired messages
     */
    public void cleanupExpiredMessages() {
        DTMessagingDatabase.databaseWriteExecutor.execute(() -> {
            try {
                long currentTime = System.currentTimeMillis();
                int deletedCount = messageDao.deleteExpiredMessages(currentTime);
                if (deletedCount > 0) {
                    LogUtil.d(TAG, "Cleaned up " + deletedCount + " expired messages");
                }
            } catch (Exception e) {
                LogUtil.e(TAG, "Failed to cleanup expired messages: " + e.getMessage());
            }
        });
    }

    /**
     * Delete a specific message
     */
    public void deleteMessage(MessageEntity message) {
        DTMessagingDatabase.databaseWriteExecutor.execute(() -> {
            try {
                messageDao.deleteMessage(message);
                LogUtil.d(TAG, "Deleted message: " + message.messageId);
            } catch (Exception e) {
                LogUtil.e(TAG, "Failed to delete message: " + e.getMessage());
            }
        });
    }
}