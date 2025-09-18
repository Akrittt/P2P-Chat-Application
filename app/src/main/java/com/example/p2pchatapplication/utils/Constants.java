package com.example.p2pchatapplication.utils;

/**
 * Application-wide constants
 */
public class Constants {

    // Networking
    public static final String SERVICE_ID = "com.example.p2pchatapplication";
    public static final String STRATEGY = "P2P_CLUSTER"; // For Nearby Connections

    // Message Protocol
    public static final int MESSAGE_TYPE_TEXT = 1;
    public static final int MESSAGE_TYPE_ACK = 2;
    public static final int MESSAGE_TYPE_FORWARD = 3;

    // Connection timeouts
    public static final int CONNECTION_TIMEOUT_MS = 30000; // 30 seconds
    public static final int DISCOVERY_TIMEOUT_MS = 60000;  // 60 seconds

    // Store and forward
    public static final int MAX_HOP_COUNT = 5;
    public static final long DEFAULT_MESSAGE_TTL_HOURS = 24;

    // Security
    public static final String ENCRYPTION_ALGORITHM = "AES/CBC/PKCS7Padding";
    public static final String HASH_ALGORITHM = "SHA-256";
    public static final int KEY_LENGTH = 256; // bits

    // Preferences
    public static final String PREFS_NAME = "dt_messaging_prefs";
    public static final String PREF_USER_ID = "user_id";
    public static final String PREF_DEVICE_NAME = "device_name";

    // Notification
    public static final int NOTIFICATION_ID = 1001;
    public static final String NOTIFICATION_CHANNEL_ID = "dt_messaging_channel";

    // Database
    public static final String DATABASE_NAME = "dt_messaging_database";
    public static final int DATABASE_VERSION = 1;

    // UI
    public static final int MAX_MESSAGE_LENGTH = 1000;
    public static final int MESSAGES_PER_PAGE = 50;
}