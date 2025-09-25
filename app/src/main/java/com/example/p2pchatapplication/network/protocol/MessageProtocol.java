package com.example.p2pchatapplication.network.protocol;


import com.example.p2pchatapplication.data.database.MessageEntity;
import com.example.p2pchatapplication.utils.Constants;
import com.example.p2pchatapplication.utils.LogUtil;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;


import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Handles message serialization/deserialization for peer-to-peer communication.
 * Implements the DTMessaging protocol for store-and-forward networking.
 */
public class MessageProtocol {

    private static final String TAG = "MessageProtocol";
    private static final Gson gson = new Gson();

    /**
     * Network message format for transmission between peers
     */
    public static class NetworkMessage {
        public int messageType;          // TEXT, ACK, FORWARD
        public String messageId;         // Unique message identifier
        public String senderId;          // Original sender ID
        public String recipientId;       // Target recipient ID
        public String content;           // Message content (may be encrypted)
        public long timestamp;           // Original timestamp
        public int hopCount;             // Number of hops so far
        public long ttl;                 // Time-to-live (expiration)
        public String hash;              // Integrity hash
        public String forwarderPath;     // Chain of forwarders (for debugging)

        // Default constructor for Gson
        public NetworkMessage() {}

        // Constructor for creating new network messages
        public NetworkMessage(int messageType, String messageId, String senderId,
                              String recipientId, String content, long timestamp,
                              int hopCount, long ttl, String hash) {
            this.messageType = messageType;
            this.messageId = messageId;
            this.senderId = senderId;
            this.recipientId = recipientId;
            this.content = content;
            this.timestamp = timestamp;
            this.hopCount = hopCount;
            this.ttl = ttl;
            this.hash = hash;
            this.forwarderPath = senderId; // Start with original sender
        }
    }

    /**
     * Convert MessageEntity to NetworkMessage for transmission
     */
    public static NetworkMessage createNetworkMessage(MessageEntity entity, String currentUserId) {
        try {
            // Generate hash for integrity verification
            String hash = generateMessageHash(entity.content, entity.senderId, entity.recipientId, entity.timestamp);

            NetworkMessage networkMessage = new NetworkMessage(
                    Constants.MESSAGE_TYPE_TEXT,
                    entity.messageId,
                    entity.senderId,
                    entity.recipientId,
                    entity.content,
                    entity.timestamp,
                    entity.hopCount,
                    entity.ttl,
                    hash
            );

            LogUtil.d(TAG, "Created network message: " + entity.messageId);
            return networkMessage;

        } catch (Exception e) {
            LogUtil.e(TAG, "Failed to create network message: " + e.getMessage());
            return null;
        }
    }

    /**
     * Convert NetworkMessage to MessageEntity for local storage
     */
    public static MessageEntity createMessageEntity(NetworkMessage networkMessage, boolean isOutgoing) {
        try {
            MessageEntity entity = new MessageEntity();
            entity.messageId = networkMessage.messageId;
            entity.senderId = networkMessage.senderId;
            entity.recipientId = networkMessage.recipientId;
            entity.content = networkMessage.content;
            entity.timestamp = networkMessage.timestamp;
            entity.hopCount = networkMessage.hopCount;
            entity.ttl = networkMessage.ttl;
            entity.hash = networkMessage.hash;
            entity.isOutgoing = isOutgoing;
            entity.status = isOutgoing ? MessageEntity.STATUS_SENT : MessageEntity.STATUS_PENDING;

            LogUtil.d(TAG, "Created message entity: " + networkMessage.messageId);
            return entity;

        } catch (Exception e) {
            LogUtil.e(TAG, "Failed to create message entity: " + e.getMessage());
            return null;
        }
    }

    /**
     * Serialize NetworkMessage to byte array for transmission
     */
    public static byte[] serializeMessage(NetworkMessage message) {
        try {
            String json = gson.toJson(message);
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

            LogUtil.d(TAG, "Serialized message: " + message.messageId +
                    " (" + bytes.length + " bytes)");
            return bytes;

        } catch (Exception e) {
            LogUtil.e(TAG, "Failed to serialize message: " + e.getMessage());
            return null;
        }
    }

    /**
     * Deserialize byte array to NetworkMessage
     */
    public static NetworkMessage deserializeMessage(byte[] data) {
        try {
            String json = new String(data, StandardCharsets.UTF_8);
            NetworkMessage message = gson.fromJson(json, NetworkMessage.class);

            if (message != null) {
                LogUtil.d(TAG, "Deserialized message: " + message.messageId);

                // Validate required fields
                if (message.messageId == null || message.senderId == null ||
                        message.content == null || message.timestamp <= 0) {
                    LogUtil.e(TAG, "Invalid message format - missing required fields");
                    return null;
                }

                return message;
            } else {
                LogUtil.e(TAG, "Failed to parse message JSON");
                return null;
            }

        } catch (JsonSyntaxException e) {
            LogUtil.e(TAG, "Invalid JSON format: " + e.getMessage());
            return null;
        } catch (Exception e) {
            LogUtil.e(TAG, "Failed to deserialize message: " + e.getMessage());
            return null;
        }
    }

    /**
     * Create an acknowledgment message
     */
    public static NetworkMessage createAckMessage(String originalMessageId, String senderId, String recipientId) {
        String ackId = "ack_" + UUID.randomUUID().toString();
        String hash = generateMessageHash("ACK", senderId, recipientId, System.currentTimeMillis());

        NetworkMessage ack = new NetworkMessage(
                Constants.MESSAGE_TYPE_ACK,
                ackId,
                senderId,
                recipientId,
                "ACK:" + originalMessageId,
                System.currentTimeMillis(),
                0, // ACKs don't need forwarding
                System.currentTimeMillis() + (60 * 1000), // 1 minute TTL for ACKs
                hash
        );

        LogUtil.d(TAG, "Created ACK message for: " + originalMessageId);
        return ack;
    }

    /**
     * Check if message has expired based on TTL
     */
    public static boolean isMessageExpired(NetworkMessage message) {
        boolean expired = System.currentTimeMillis() > message.ttl;
        if (expired) {
            LogUtil.d(TAG, "Message expired: " + message.messageId);
        }
        return expired;
    }

    /**
     * Check if message should be forwarded (not exceeded max hops)
     */
    public static boolean shouldForwardMessage(NetworkMessage message) {
        boolean shouldForward = message.hopCount < Constants.MAX_HOP_COUNT && !isMessageExpired(message);
        LogUtil.d(TAG, "Should forward message " + message.messageId + ": " + shouldForward +
                " (hops: " + message.hopCount + "/" + Constants.MAX_HOP_COUNT + ")");
        return shouldForward;
    }

    /**
     * Increment hop count for forwarding
     */
    public static NetworkMessage incrementHopCount(NetworkMessage message, String forwarderId) {
        message.hopCount++;

        // Add forwarder to path for debugging
        if (message.forwarderPath == null) {
            message.forwarderPath = message.senderId;
        }
        message.forwarderPath += " -> " + forwarderId;

        LogUtil.d(TAG, "Incremented hop count for " + message.messageId +
                " to " + message.hopCount + ". Path: " + message.forwarderPath);

        return message;
    }

    /**
     * Verify message integrity using hash
     */
    public static boolean verifyMessageIntegrity(NetworkMessage message) {
        try {
            String expectedHash = generateMessageHash(message.content, message.senderId,
                    message.recipientId, message.timestamp);
            boolean valid = expectedHash.equals(message.hash);

            if (!valid) {
                LogUtil.w(TAG, "Message integrity check failed for: " + message.messageId);
            }

            return valid;

        } catch (Exception e) {
            LogUtil.e(TAG, "Failed to verify message integrity: " + e.getMessage());
            return false;
        }
    }

    /**
     * Generate SHA-256 hash for message integrity
     */
    private static String generateMessageHash(String content, String senderId, String recipientId, long timestamp) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String data = content + senderId + recipientId + timestamp;
            byte[] hash = digest.digest(data.getBytes(StandardCharsets.UTF_8));

            // Convert to hex string
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }

            return hexString.toString();

        } catch (NoSuchAlgorithmException e) {
            LogUtil.e(TAG, "SHA-256 algorithm not available: " + e.getMessage());
            return "hash_error_" + System.currentTimeMillis();
        }
    }
}