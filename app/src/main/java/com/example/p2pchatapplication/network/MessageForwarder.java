package com.example.p2pchatapplication.network;

import com.example.p2pchatapplication.data.repository.MessageRepository;
import com.example.p2pchatapplication.network.protocol.MessageProtocol;
import com.example.p2pchatapplication.utils.LogUtil;
import com.example.p2pchatapplication.data.database.MessageEntity;



import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Handles store-and-forward logic for delay-tolerant messaging.
 * Manages message routing, forwarding, and duplicate detection.
 */
public class MessageForwarder {

    private static final String TAG = "MessageForwarder";

    private MessageRepository repository;
    private NearbyConnectionManager connectionManager;
    private String currentUserId;

    // Thread pool for background processing
    private ExecutorService executorService;

    // Set to track messages we've already seen/forwarded (prevent loops)
    private Set<String> processedMessages;

    // Callback for forwarding events
    public interface ForwardingCallback {
        void onMessageForwarded(String messageId, int peerCount);
        void onMessageReceived(String messageId, String senderId);
        void onDuplicateMessageFiltered(String messageId);
    }

    private ForwardingCallback callback;

    public MessageForwarder(MessageRepository repository, NearbyConnectionManager connectionManager, String currentUserId) {
        this.repository = repository;
        this.connectionManager = connectionManager;
        this.currentUserId = currentUserId;
        this.executorService = Executors.newSingleThreadExecutor();
        this.processedMessages = new HashSet<>();

        LogUtil.d(TAG, "MessageForwarder initialized for user: " + currentUserId);
    }

    public void setCallback(ForwardingCallback callback) {
        this.callback = callback;
    }

    /**
     * Process an incoming network message from a peer
     */
    public void processIncomingMessage(String fromEndpointId, MessageProtocol.NetworkMessage networkMessage) {
        executorService.execute(() -> {
            try {
                LogUtil.d(TAG, "Processing incoming message: " + networkMessage.messageId +
                        " from " + fromEndpointId);

                // Check if message has expired
                if (MessageProtocol.isMessageExpired(networkMessage)) {
                    LogUtil.d(TAG, "Discarding expired message: " + networkMessage.messageId);
                    return;
                }

                // Check if we've already processed this message (prevent loops)
                if (processedMessages.contains(networkMessage.messageId)) {
                    LogUtil.d(TAG, "Duplicate message filtered: " + networkMessage.messageId);
                    if (callback != null) {
                        callback.onDuplicateMessageFiltered(networkMessage.messageId);
                    }
                    return;
                }

                // Verify message integrity
                if (!MessageProtocol.verifyMessageIntegrity(networkMessage)) {
                    LogUtil.w(TAG, "Message integrity check failed: " + networkMessage.messageId);
                    return;
                }

                // Mark as processed
                processedMessages.add(networkMessage.messageId);

                // Handle different message types
                switch (networkMessage.messageType) {
                    case com.example.p2pchatapplication.utils.Constants.MESSAGE_TYPE_TEXT:
                        handleTextMessage(networkMessage);
                        break;

                    case com.example.p2pchatapplication.utils.Constants.MESSAGE_TYPE_ACK:
                        handleAckMessage(networkMessage);
                        break;

                    default:
                        LogUtil.w(TAG, "Unknown message type: " + networkMessage.messageType);
                }

            } catch (Exception e) {
                LogUtil.e(TAG, "Error processing incoming message: " + e.getMessage());
            }
        });
    }

    /**
     * Handle incoming text message
     */
    private void handleTextMessage(MessageProtocol.NetworkMessage networkMessage) {
        try {
            // Check if this message is for us
            boolean isForUs = networkMessage.recipientId.equals(currentUserId) ||
                    networkMessage.recipientId.equals("broadcast");

            // Store message locally if it's for us or we need to forward it
            MessageEntity entity = MessageProtocol.createMessageEntity(networkMessage, false);
            if (entity != null) {
                repository.insertMessage(entity);

                if (isForUs) {
                    LogUtil.d(TAG, "Message delivered to us: " + networkMessage.messageId);
                    // Update status to delivered
                    repository.updateMessageStatus(networkMessage.messageId, MessageEntity.STATUS_DELIVERED);

                    if (callback != null) {
                        callback.onMessageReceived(networkMessage.messageId, networkMessage.senderId);
                    }

                    // Send ACK back to sender (if not broadcast)
                    if (!networkMessage.recipientId.equals("broadcast")) {
                        sendAckMessage(networkMessage);
                    }
                } else {
                    LogUtil.d(TAG, "Message stored for forwarding: " + networkMessage.messageId);
                }

                // Forward message to other peers if appropriate
                forwardMessageToPeers(networkMessage);
            }

        } catch (Exception e) {
            LogUtil.e(TAG, "Error handling text message: " + e.getMessage());
        }
    }

    /**
     * Handle incoming acknowledgment message
     */
    private void handleAckMessage(MessageProtocol.NetworkMessage ackMessage) {
        try {
            // Extract original message ID from ACK content
            String content = ackMessage.content;
            if (content != null && content.startsWith("ACK:")) {
                String originalMessageId = content.substring(4);

                // Update original message status to delivered
                repository.updateMessageStatus(originalMessageId, MessageEntity.STATUS_DELIVERED);
                LogUtil.d(TAG, "Received ACK for message: " + originalMessageId);
            }

        } catch (Exception e) {
            LogUtil.e(TAG, "Error handling ACK message: " + e.getMessage());
        }
    }

    /**
     * Forward message to connected peers
     */
    private void forwardMessageToPeers(MessageProtocol.NetworkMessage networkMessage) {
        try {
            // Check if we should forward this message
            if (!MessageProtocol.shouldForwardMessage(networkMessage)) {
                LogUtil.d(TAG, "Not forwarding message (hop limit or expired): " + networkMessage.messageId);
                return;
            }

            // Get connected peers
            Set<String> connectedPeers = connectionManager.getConnectedEndpoints();
            if (connectedPeers.isEmpty()) {
                LogUtil.d(TAG, "No connected peers to forward message to");
                return;
            }

            // Increment hop count for forwarding
            MessageProtocol.NetworkMessage forwardMessage = MessageProtocol.incrementHopCount(networkMessage, currentUserId);

            // Serialize message
            byte[] messageData = MessageProtocol.serializeMessage(forwardMessage);
            if (messageData == null) {
                LogUtil.e(TAG, "Failed to serialize message for forwarding");
                return;
            }

            // Forward to all connected peers
            int forwardedCount = 0;
            for (String peerId : connectedPeers) {
                connectionManager.sendMessage(peerId, messageData);
                forwardedCount++;
            }

            if (forwardedCount > 0) {
                LogUtil.d(TAG, "Forwarded message " + networkMessage.messageId +
                        " to " + forwardedCount + " peers");

                if (callback != null) {
                    callback.onMessageForwarded(networkMessage.messageId, forwardedCount);
                }
            }

        } catch (Exception e) {
            LogUtil.e(TAG, "Error forwarding message: " + e.getMessage());
        }
    }

    /**
     * Send acknowledgment message back to original sender
     */
    private void sendAckMessage(MessageProtocol.NetworkMessage originalMessage) {
        try {
            MessageProtocol.NetworkMessage ackMessage = MessageProtocol.createAckMessage(
                    originalMessage.messageId,
                    currentUserId,
                    originalMessage.senderId
            );

            byte[] ackData = MessageProtocol.serializeMessage(ackMessage);
            if (ackData != null) {
                // Try to send ACK back through all connected peers
                connectionManager.broadcastMessage(ackData);
                LogUtil.d(TAG, "Sent ACK for message: " + originalMessage.messageId);
            }

        } catch (Exception e) {
            LogUtil.e(TAG, "Error sending ACK message: " + e.getMessage());
        }
    }

    /**
     * Send outgoing message to network
     */
    public void sendMessage(MessageEntity messageEntity) {
        executorService.execute(() -> {
            try {
                LogUtil.d(TAG, "Sending outgoing message: " + messageEntity.messageId);

                // Convert to network message
                MessageProtocol.NetworkMessage networkMessage =
                        MessageProtocol.createNetworkMessage(messageEntity, currentUserId);

                if (networkMessage == null) {
                    LogUtil.e(TAG, "Failed to create network message");
                    repository.updateMessageStatus(messageEntity.messageId, MessageEntity.STATUS_FAILED);
                    return;
                }

                // Serialize message
                byte[] messageData = MessageProtocol.serializeMessage(networkMessage);
                if (messageData == null) {
                    LogUtil.e(TAG, "Failed to serialize outgoing message");
                    repository.updateMessageStatus(messageEntity.messageId, MessageEntity.STATUS_FAILED);
                    return;
                }

                // Mark as processed to avoid forwarding back to ourselves
                processedMessages.add(messageEntity.messageId);

                // Send to all connected peers
                if (connectionManager.isConnectedToAnyPeer()) {
                    connectionManager.broadcastMessage(messageData);
                    repository.updateMessageStatus(messageEntity.messageId, MessageEntity.STATUS_SENT);

                    LogUtil.d(TAG, "Message broadcast to " +
                            connectionManager.getConnectedPeerCount() + " peers");
                } else {
                    LogUtil.d(TAG, "No connected peers - message remains pending");
                    // Message stays in PENDING status for later transmission
                }

            } catch (Exception e) {
                LogUtil.e(TAG, "Error sending message: " + e.getMessage());
                repository.updateMessageStatus(messageEntity.messageId, MessageEntity.STATUS_FAILED);
            }
        });
    }

    /**
     * Process pending messages when new peer connects
     */
    public void processPendingMessages() {
        executorService.execute(() -> {
            try {
                LogUtil.d(TAG, "Processing pending messages due to new peer connection");

                // Get all pending messages from repository
                repository.getPendingMessages().get().forEach(messageEntity -> {
                    if (messageEntity != null && messageEntity.isOutgoing) {
                        // Only send our own pending messages
                        sendMessage(messageEntity);
                    }
                });

                // Also get messages that need forwarding
                repository.getMessagesToForward().get().forEach(messageEntity -> {
                    if (messageEntity != null && !messageEntity.isOutgoing) {
                        // Forward messages from others
                        MessageProtocol.NetworkMessage networkMessage =
                                MessageProtocol.createNetworkMessage(messageEntity, currentUserId);

                        if (networkMessage != null && MessageProtocol.shouldForwardMessage(networkMessage)) {
                            forwardMessageToPeers(networkMessage);
                        }
                    }
                });

            } catch (Exception e) {
                LogUtil.e(TAG, "Error processing pending messages: " + e.getMessage());
            }
        });
    }

    /**
     * Clean up expired messages and processed message history
     */
    public void cleanup() {
        executorService.execute(() -> {
            try {
                // Clean up expired messages from database
                repository.cleanupExpiredMessages();

                // Clear old processed message IDs to prevent memory leak
                // Keep only recent ones (last 1000 messages)
                if (processedMessages.size() > 1000) {
                    LogUtil.d(TAG, "Clearing old processed message IDs");
                    processedMessages.clear();
                }

                LogUtil.d(TAG, "Cleanup completed");

            } catch (Exception e) {
                LogUtil.e(TAG, "Error during cleanup: " + e.getMessage());
            }
        });
    }

    /**
     * Get statistics for monitoring
     */
    public void logStatistics() {
        LogUtil.d(TAG, "=== Message Forwarder Statistics ===");
        LogUtil.d(TAG, "Connected peers: " + connectionManager.getConnectedPeerCount());
        LogUtil.d(TAG, "Processed messages: " + processedMessages.size());
        LogUtil.d(TAG, "Current user ID: " + currentUserId);
        LogUtil.d(TAG, "===================================");
    }

    /**
     * Shutdown the message forwarder
     */
    public void shutdown() {
        try {
            executorService.shutdown();
            processedMessages.clear();
            LogUtil.d(TAG, "MessageForwarder shutdown complete");
        } catch (Exception e) {
            LogUtil.e(TAG, "Error during shutdown: " + e.getMessage());
        }
    }
}