package com.example.p2pchatapplication.data.database;

import androidx.room.Entity;
import androidx.room.PrimaryKey;
import androidx.room.ColumnInfo;

/**
 * Room Entity representing a message in the local database.
 * This stores messages for store-and-forward functionality.
 */
@Entity(tableName = "messages")
public class MessageEntity {

    @PrimaryKey
    @ColumnInfo(name = "message_id")
    public String messageId;

    @ColumnInfo(name = "content")
    public String content;

    @ColumnInfo(name = "sender_id")
    public String senderId;

    @ColumnInfo(name = "recipient_id")
    public String recipientId;

    @ColumnInfo(name = "timestamp")
    public long timestamp;

    @ColumnInfo(name = "status")
    public int status; // 0=pending, 1=sent, 2=delivered, 3=failed

    @ColumnInfo(name = "hop_count")
    public int hopCount; // Number of hops for multi-hop routing

    @ColumnInfo(name = "ttl") // Time-to-live in minutes
    public long ttl;

    @ColumnInfo(name = "encrypted_content")
    public String encryptedContent; // AES encrypted message content

    @ColumnInfo(name = "hash")
    public String hash; // SHA-256 hash for integrity verification

    @ColumnInfo(name = "is_outgoing")
    public boolean isOutgoing; // true if sent by this device, false if received

    // Default constructor required by Room
    public MessageEntity() {
    }

    // Constructor for creating new messages
    public MessageEntity(String messageId, String content, String senderId,
                         String recipientId, long timestamp, int status,
                         int hopCount, long ttl, boolean isOutgoing) {
        this.messageId = messageId;
        this.content = content;
        this.senderId = senderId;
        this.recipientId = recipientId;
        this.timestamp = timestamp;
        this.status = status;
        this.hopCount = hopCount;
        this.ttl = ttl;
        this.isOutgoing = isOutgoing;
    }

    // Status constants
    public static final int STATUS_PENDING = 0;
    public static final int STATUS_SENT = 1;
    public static final int STATUS_DELIVERED = 2;
    public static final int STATUS_FAILED = 3;

    // Default TTL: 24 hours in minutes
    public static final long DEFAULT_TTL_MINUTES = 24 * 60;
}