package com.example.p2pchatapplication.ui.viewmodels;

import android.app.Application;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;


import com.example.p2pchatapplication.data.database.MessageEntity;
import com.example.p2pchatapplication.data.repository.MessageRepository;

import java.util.List;

/**
 * ViewModel for Chat functionality using MVVM architecture
 * Manages UI-related data and survives configuration changes
 */
public class ChatViewModel extends AndroidViewModel {

    private MessageRepository repository;
    private LiveData<List<MessageEntity>> allMessages;
    private LiveData<Integer> messageCount;
    private LiveData<Integer> pendingMessageCount;

    public ChatViewModel(@NonNull Application application) {
        super(application);
        repository = new MessageRepository(application);
        allMessages = repository.getAllMessages();
        messageCount = repository.getMessageCount();
        pendingMessageCount = repository.getPendingMessageCount();
    }

    /**
     * Get all messages as LiveData
     */
    public LiveData<List<MessageEntity>> getAllMessages() {
        return allMessages;
    }

    /**
     * Get conversation between two users (NEW for friends feature)
     */
    public LiveData<List<MessageEntity>> getConversation(String userId1, String userId2) {
        return repository.getConversation(userId1, userId2);
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
        return pendingMessageCount;
    }

    /**
     * Insert a new message
     */
    public void insertMessage(MessageEntity message) {
        repository.insertMessage(message);
    }

    /**
     * Insert multiple messages
     */
    public void insertMessages(List<MessageEntity> messages) {
        repository.insertMessages(messages);
    }

    /**
     * Update message status
     */
    public void updateMessageStatus(String messageId, int status) {
        repository.updateMessageStatus(messageId, status);
    }

    /**
     * Clean up expired messages
     */
    public void cleanupExpiredMessages() {
        repository.cleanupExpiredMessages();
    }

    /**
     * Delete a specific message
     */
    public void deleteMessage(MessageEntity message) {
        repository.deleteMessage(message);
    }
}