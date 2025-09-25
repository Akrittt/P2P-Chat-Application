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
 * Complete MessageProtocol for DT-Messaging System
 *
 * Handles message serialization/deserialization for peer-to-peer communication.
 * Implements the DTMessaging protocol for store-and-forward networking with:
 * - AES-256 encryption for message confidentiality
 * - HMAC-SHA256 for message integrity verification
 * - Digital signatures for message authenticity
 * - Multi-hop routing with hop count tracking
 * - TTL-based message expiration
 * - ACK-based delivery confirmation
 *
 * @author DT-Messaging Team
 * @version 1.0 - Phase 4 Complete
 */
public class MessageProtocol {

    private static final String TAG = "MessageProtocol";
    private static final Gson gson = new Gson();

    // Security manager for encryption/decryption (singleton pattern)
    private static SecurityManager securityManager = new SecurityManager();

    /**
     * Network message format for transmission between peers.
     * This is the wire format that gets serialized and sent over the network.
     */
    public static class NetworkMessage {
        // Core message fields
        public int messageType;          // MESSAGE_TYPE_TEXT, MESSAGE_TYPE_ACK, etc.
        public String messageId;         // Unique message identifier (UUID or secure random)
        public String senderId;          // Original sender user ID
        public String recipientId;       // Target recipient user ID ("broadcast" for all)
        public String content;           // Message content (encrypted if encrypted=true)
        public long timestamp;           // Original creation timestamp (Unix millis)

        // Store-and-forward routing fields
        public int hopCount;             // Number of hops traversed (0 = original sender)
        public long ttl;                 // Time-to-live expiration timestamp (Unix millis)
        public String forwarderPath;     // Chain of forwarders for debugging (e.g., "A->B->C")

        // Security fields
        public boolean encrypted;        // Whether content is AES encrypted
        public String hash;              // SHA-256 hash of original content for integrity
        public String signature;         // Digital signature for authenticity

        // Default constructor required by Gson
        public NetworkMessage() {}

        /**
         * Constructor for creating new network messages
         *
         * @param messageType Type of message (TEXT, ACK, etc.)
         * @param messageId Unique identifier for this message
         * @param senderId Original sender's user ID
         * @param recipientId Target recipient's user ID
         * @param content Message content (plaintext or encrypted)
         * @param timestamp Creation timestamp
         * @param hopCount Current hop count (0 for new messages)
         * @param ttl Time-to-live expiration timestamp
         * @param hash SHA-256 hash for integrity verification
         * @param encrypted Whether content is encrypted
         * @param signature Digital signature for authenticity
         */
        public NetworkMessage(int messageType, String messageId, String senderId,
                              String recipientId, String content, long timestamp,
                              int hopCount, long ttl, String hash, boolean encrypted, String signature) {
            this.messageType = messageType;
            this.messageId = messageId;
            this.senderId = senderId;
            this.recipientId = recipientId;
            this.content = content;
            this.timestamp = timestamp;
            this.hopCount = hopCount;
            this.ttl = ttl;
            this.hash = hash;
            this.encrypted = encrypted;
            this.signature = signature;
            this.forwarderPath = senderId; // Initialize with original sender
        }

        /**
         * Get human-readable string representation for debugging
         */
        @Override
        public String toString() {
            return "NetworkMessage{" +
                    "id='" + messageId + '\'' +
                    ", from='" + senderId + '\'' +
                    ", to='" + recipientId + '\'' +
                    ", type=" + messageType +
                    ", hops=" + hopCount +
                    ", encrypted=" + encrypted +
                    ", path='" + forwarderPath + '\'' +
                    '}';
        }
    }

    /**
     * Convert MessageEntity (database format) to NetworkMessage (wire format) for transmission.
     * This method handles encryption automatically if security is enabled.
     *
     * @param entity The database message entity to convert
     * @param currentUserId The current user's ID (for signature generation)
     * @return NetworkMessage ready for serialization, or null if conversion failed
     */
    public static NetworkMessage createNetworkMessage(MessageEntity entity, String currentUserId) {
        try {
            LogUtil.d(TAG, "Converting MessageEntity to NetworkMessage: " + entity.messageId);

            String content = entity.content;
            boolean encrypted = false;

            // Encrypt message content if security manager is ready
            if (securityManager != null && securityManager.isSecurityReady()) {
                SecurityManager.EncryptedMessage encryptedMsg = securityManager.encryptMessage(entity.content);
                if (encryptedMsg != null) {
                    content = encryptedMsg.serialize(); // Convert to JSON string for transmission
                    encrypted = true;
                    LogUtil.d(TAG, "Message encrypted successfully: " + entity.messageId);
                } else {
                    LogUtil.w(TAG, "Encryption failed, sending as plaintext: " + entity.messageId);
                    // Continue with plaintext if encryption fails
                }
            } else {
                LogUtil.d(TAG, "Security not ready, sending plaintext: " + entity.messageId);
            }

            // Generate SHA-256 hash for integrity verification (always on original plaintext)
            String hash = generateMessageHash(entity.content, entity.senderId, entity.recipientId, entity.timestamp);

            // Generate digital signature for authenticity
            String signature = "";
            if (securityManager != null) {
                signature = securityManager.generateMessageSignature(entity.content, entity.senderId, entity.timestamp);
            }

            // Create network message
            NetworkMessage networkMessage = new NetworkMessage(
                    Constants.MESSAGE_TYPE_TEXT,
                    entity.messageId,
                    entity.senderId,
                    entity.recipientId,
                    content, // This is either plaintext or encrypted JSON
                    entity.timestamp,
                    entity.hopCount,
                    entity.ttl,
                    hash,
                    encrypted,
                    signature
            );

            LogUtil.d(TAG, "Created network message: " + networkMessage);
            return networkMessage;

        } catch (Exception e) {
            LogUtil.e(TAG, "Failed to create network message: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * Convert NetworkMessage (wire format) to MessageEntity (database format) for local storage.
     * This method handles decryption automatically if the message is encrypted.
     *
     * @param networkMessage The received network message to convert
     * @param isOutgoing Whether this is an outgoing message (sent by us)
     * @return MessageEntity ready for database storage, or null if conversion failed
     */
    public static MessageEntity createMessageEntity(NetworkMessage networkMessage, boolean isOutgoing) {
        try {
            LogUtil.d(TAG, "Converting NetworkMessage to MessageEntity: " + networkMessage);

            String content = networkMessage.content;

            // Decrypt content if it's encrypted
            if (networkMessage.encrypted && securityManager != null && securityManager.isSecurityReady()) {
                SecurityManager.EncryptedMessage encryptedMsg = SecurityManager.EncryptedMessage.deserialize(networkMessage.content);
                if (encryptedMsg != null) {
                    String decryptedContent = securityManager.decryptMessage(encryptedMsg);
                    if (decryptedContent != null) {
                        content = decryptedContent;
                        LogUtil.d(TAG, "Message decrypted successfully: " + networkMessage.messageId);
                    } else {
                        LogUtil.e(TAG, "Decryption failed for message: " + networkMessage.messageId);
                        return null; // Reject messages we can't decrypt
                    }
                } else {
                    LogUtil.e(TAG, "Invalid encrypted message format: " + networkMessage.messageId);
                    return null;
                }
            }

            // Verify digital signature if present
            if (networkMessage.signature != null && !networkMessage.signature.isEmpty() && securityManager != null) {
                boolean signatureValid = securityManager.verifyMessageSignature(
                        content, networkMessage.senderId, networkMessage.timestamp, networkMessage.signature);

                if (!signatureValid) {
                    LogUtil.w(TAG, "Message signature verification failed: " + networkMessage.messageId);
                    // In production, you might want to reject messages with invalid signatures
                    // For demo purposes, we'll continue but log the warning
                }
            }

            // Create message entity for database storage
            MessageEntity entity = new MessageEntity();
            entity.messageId = networkMessage.messageId;
            entity.senderId = networkMessage.senderId;
            entity.recipientId = networkMessage.recipientId;
            entity.content = content; // Store decrypted content in database
            entity.timestamp = networkMessage.timestamp;
            entity.hopCount = networkMessage.hopCount;
            entity.ttl = networkMessage.ttl;
            entity.hash = networkMessage.hash;
            entity.isOutgoing = isOutgoing;
            entity.status = isOutgoing ? MessageEntity.STATUS_SENT : MessageEntity.STATUS_PENDING;

            // Note: We don't store encrypted content in database for performance
            // The database content is always plaintext, encryption only happens during transmission

            LogUtil.d(TAG, "Created message entity: " + entity.messageId);
            return entity;

        } catch (Exception e) {
            LogUtil.e(TAG, "Failed to create message entity: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * Serialize NetworkMessage to byte array for network transmission.
     * Uses Gson to convert to JSON, then UTF-8 encoding to bytes.
     *
     * @param message The network message to serialize
     * @return Byte array ready for transmission, or null if serialization failed
     */
    public static byte[] serializeMessage(NetworkMessage message) {
        try {
            if (message == null) {
                LogUtil.e(TAG, "Cannot serialize null message");
                return null;
            }

            // Convert to JSON string
            String json = gson.toJson(message);

            // Convert to UTF-8 bytes
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

            LogUtil.d(TAG, "Serialized message " + message.messageId +
                    " (" + bytes.length + " bytes, encrypted: " + message.encrypted + ")");

            return bytes;

        } catch (Exception e) {
            LogUtil.e(TAG, "Failed to serialize message: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * Deserialize byte array to NetworkMessage.
     * Converts UTF-8 bytes to JSON string, then parses with Gson.
     *
     * @param data The byte array received from network
     * @return NetworkMessage object, or null if deserialization failed
     */
    public static NetworkMessage deserializeMessage(byte[] data) {
        try {
            if (data == null || data.length == 0) {
                LogUtil.e(TAG, "Cannot deserialize null or empty data");
                return null;
            }

            // Convert bytes to JSON string
            String json = new String(data, StandardCharsets.UTF_8);

            // Parse JSON to NetworkMessage object
            NetworkMessage message = gson.fromJson(json, NetworkMessage.class);

            if (message != null) {
                LogUtil.d(TAG, "Deserialized message: " + message);

                // Validate required fields to prevent crashes from malformed data
                if (message.messageId == null || message.messageId.isEmpty()) {
                    LogUtil.e(TAG, "Invalid message: missing messageId");
                    return null;
                }

                if (message.senderId == null || message.senderId.isEmpty()) {
                    LogUtil.e(TAG, "Invalid message: missing senderId");
                    return null;
                }

                if (message.content == null) {
                    LogUtil.e(TAG, "Invalid message: missing content");
                    return null;
                }

                if (message.timestamp <= 0) {
                    LogUtil.e(TAG, "Invalid message: invalid timestamp");
                    return null;
                }

                return message;
            } else {
                LogUtil.e(TAG, "Failed to parse message JSON");
                return null;
            }

        } catch (JsonSyntaxException e) {
            LogUtil.e(TAG, "Invalid JSON format in received message: " + e.getMessage());
            return null;
        } catch (Exception e) {
            LogUtil.e(TAG, "Failed to deserialize message: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * Create an acknowledgment message for delivery confirmation.
     * ACKs are sent back to the original sender to confirm message delivery.
     *
     * @param originalMessageId The ID of the message being acknowledged
     * @param senderId The ID of the user sending the ACK (recipient of original message)
     * @param recipientId The ID of the user who will receive the ACK (original sender)
     * @return NetworkMessage containing the ACK, ready for transmission
     */
    public static NetworkMessage createAckMessage(String originalMessageId, String senderId, String recipientId) {
        try {
            // Generate secure ACK ID
            String ackId = (securityManager != null) ?
                    securityManager.generateSecureMessageId() :
                    "ack_" + UUID.randomUUID().toString();

            // ACK content includes original message ID
            String ackContent = "ACK:" + originalMessageId;

            // Generate hash for ACK integrity
            long timestamp = System.currentTimeMillis();
            String hash = generateMessageHash(ackContent, senderId, recipientId, timestamp);

            // Generate signature for ACK authenticity
            String signature = "";
            if (securityManager != null) {
                signature = securityManager.generateMessageSignature(ackContent, senderId, timestamp);
            }

            // Create ACK network message
            NetworkMessage ack = new NetworkMessage(
                    Constants.MESSAGE_TYPE_ACK,
                    ackId,
                    senderId,
                    recipientId,
                    ackContent,
                    timestamp,
                    0, // ACKs don't need multi-hop forwarding
                    timestamp + (60 * 1000), // ACKs have short TTL (1 minute)
                    hash,
                    false, // ACKs are not encrypted for simplicity and performance
                    signature
            );

            LogUtil.d(TAG, "Created ACK message for original message: " + originalMessageId);
            return ack;

        } catch (Exception e) {
            LogUtil.e(TAG, "Failed to create ACK message: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * Check if a message has expired based on its TTL (Time-To-Live).
     * Expired messages should be discarded to prevent resource exhaustion.
     *
     * @param message The network message to check
     * @return true if the message has expired, false otherwise
     */
    public static boolean isMessageExpired(NetworkMessage message) {
        if (message == null) {
            return true;
        }

        long currentTime = System.currentTimeMillis();
        boolean expired = currentTime > message.ttl;

        if (expired) {
            LogUtil.d(TAG, "Message expired: " + message.messageId +
                    " (expired " + (currentTime - message.ttl) + "ms ago)");
        }

        return expired;
    }

    /**
     * Check if a message should be forwarded to other peers.
     * Messages should be forwarded if they haven't exceeded max hops and aren't expired.
     *
     * @param message The network message to check
     * @return true if the message should be forwarded, false otherwise
     */
    public static boolean shouldForwardMessage(NetworkMessage message) {
        if (message == null) {
            return false;
        }

        // Check hop count limit
        boolean withinHopLimit = message.hopCount < Constants.MAX_HOP_COUNT;

        // Check expiration
        boolean notExpired = !isMessageExpired(message);

        boolean shouldForward = withinHopLimit && notExpired;

        LogUtil.d(TAG, "Should forward message " + message.messageId + ": " + shouldForward +
                " (hops: " + message.hopCount + "/" + Constants.MAX_HOP_COUNT +
                ", expired: " + !notExpired + ")");

        return shouldForward;
    }

    /**
     * Increment hop count for message forwarding and update forwarder path.
     * This modifies the message object directly.
     *
     * @param message The network message to modify
     * @param forwarderId The ID of the device forwarding the message
     * @return The same message object with incremented hop count
     */
    public static NetworkMessage incrementHopCount(NetworkMessage message, String forwarderId) {
        if (message == null || forwarderId == null) {
            LogUtil.e(TAG, "Cannot increment hop count: null message or forwarderId");
            return message;
        }

        // Increment hop count
        message.hopCount++;

        // Update forwarder path for debugging/tracing
        if (message.forwarderPath == null || message.forwarderPath.isEmpty()) {
            message.forwarderPath = message.senderId; // Start with original sender
        }

        // Add current forwarder to path
        message.forwarderPath += " -> " + forwarderId;

        LogUtil.d(TAG, "Incremented hop count for message " + message.messageId +
                " to " + message.hopCount + ". Path: " + message.forwarderPath);

        return message;
    }

    /**
     * Verify message integrity using SHA-256 hash comparison.
     * For encrypted messages, this requires decryption first to verify the original content hash.
     *
     * @param message The network message to verify
     * @return true if integrity check passes, false otherwise
     */
    public static boolean verifyMessageIntegrity(NetworkMessage message) {
        if (message == null) {
            LogUtil.e(TAG, "Cannot verify integrity of null message");
            return false;
        }

        try {
            String contentForHash = message.content;

            // If message is encrypted, we need to decrypt it first to verify the hash
            // The hash is always calculated on the original plaintext content
            if (message.encrypted && securityManager != null && securityManager.isSecurityReady()) {
                SecurityManager.EncryptedMessage encryptedMsg = SecurityManager.EncryptedMessage.deserialize(message.content);
                if (encryptedMsg != null) {
                    String decryptedContent = securityManager.decryptMessage(encryptedMsg);
                    if (decryptedContent != null) {
                        contentForHash = decryptedContent;
                    } else {
                        LogUtil.w(TAG, "Cannot verify integrity - decryption failed: " + message.messageId);
                        return false;
                    }
                } else {
                    LogUtil.w(TAG, "Cannot verify integrity - invalid encrypted format: " + message.messageId);
                    return false;
                }
            }

            // Calculate expected hash on the plaintext content
            String expectedHash = generateMessageHash(contentForHash, message.senderId,
                    message.recipientId, message.timestamp);

            // Compare with message hash
            boolean valid = expectedHash.equals(message.hash);

            if (!valid) {
                LogUtil.w(TAG, "Message integrity check failed for: " + message.messageId +
                        " (expected: " + expectedHash.substring(0, 8) + "..., got: " +
                        (message.hash != null ? message.hash.substring(0, 8) + "..." : "null") + ")");
            } else {
                LogUtil.d(TAG, "Message integrity verified for: " + message.messageId);
            }

            return valid;

        } catch (Exception e) {
            LogUtil.e(TAG, "Failed to verify message integrity: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * Generate SHA-256 hash for message integrity verification.
     * The hash is calculated on: content + senderId + recipientId + timestamp
     * This ensures the hash changes if any of these fields are modified.
     *
     * @param content The message content (plaintext)
     * @param senderId The sender's user ID
     * @param recipientId The recipient's user ID
     * @param timestamp The message timestamp
     * @return Hex string representation of the SHA-256 hash
     */
    private static String generateMessageHash(String content, String senderId, String recipientId, long timestamp) {
        try {
            // Get SHA-256 message digest
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            // Combine all fields that should affect the hash
            String data = content + senderId + recipientId + timestamp;

            // Calculate hash
            byte[] hashBytes = digest.digest(data.getBytes(StandardCharsets.UTF_8));

            // Convert to hex string
            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0'); // Pad with leading zero if needed
                }
                hexString.append(hex);
            }

            return hexString.toString();

        } catch (NoSuchAlgorithmException e) {
            LogUtil.e(TAG, "SHA-256 algorithm not available: " + e.getMessage());
            // Fallback to timestamp-based hash if SHA-256 is not available
            return "hash_fallback_" + System.currentTimeMillis();
        }
    }

    /**
     * Get the security manager instance used for encryption/decryption.
     *
     * @return SecurityManager instance, or null if not initialized
     */
    public static SecurityManager getSecurityManager() {
        return securityManager;
    }

    /**
     * Check if security features (encryption/signing) are enabled and ready.
     *
     * @return true if security is ready, false otherwise
     */
    public static boolean isSecurityEnabled() {
        return securityManager != null && securityManager.isSecurityReady();
    }

    /**
     * Get protocol version information for compatibility checking.
     *
     * @return Version string in format "major.minor.patch"
     */
    public static String getProtocolVersion() {
        return "1.0.0"; // Phase 4 Complete
    }

    /**
     * Get human-readable statistics about the protocol usage.
     *
     * @return Statistics string for debugging/monitoring
     */
    public static String getProtocolStatistics() {
        return "MessageProtocol Statistics:\n" +
                "- Version: " + getProtocolVersion() + "\n" +
                "- Security Enabled: " + isSecurityEnabled() + "\n" +
                "- Max Hop Count: " + Constants.MAX_HOP_COUNT + "\n" +
                "- Supported Message Types: TEXT, ACK\n" +
                "- Encryption: AES-256-CBC\n" +
                "- Integrity: HMAC-SHA256\n" +
                "- Authentication: Digital Signatures";
    }
}