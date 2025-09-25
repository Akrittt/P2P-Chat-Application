package com.example.p2pchatapplication.network.protocol;

import android.util.Base64;


import com.example.p2pchatapplication.utils.LogUtil;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Handles encryption, decryption, and integrity verification for secure messaging.
 * Uses AES-256-CBC for encryption and SHA-256 for integrity checking.
 */
public class SecurityManager {

    private static final String TAG = "SecurityManager";

    // Encryption parameters
    private static final String AES_TRANSFORMATION = "AES/CBC/PKCS7Padding";
    private static final String KEY_ALGORITHM = "AES";
    private static final int IV_LENGTH = 16; // 128 bits for AES
    private static final int KEY_LENGTH = 32; // 256 bits for AES-256

    // Demo encryption key (in production, use proper key exchange)
    // WARNING: This is for demonstration only! Use proper key management in production
    private static final String DEMO_KEY_BASE64 = "DEMO_KEY_32_BYTES_FOR_AES_256_ENCRYPTION_PLACEHOLDER";

    private SecretKey encryptionKey;
    private SecureRandom secureRandom;

    public SecurityManager() {
        try {
            secureRandom = new SecureRandom();

            // Initialize with demo key (replace with proper key management)
            initializeDemoKey();

            LogUtil.d(TAG, "SecurityManager initialized");
        } catch (Exception e) {
            LogUtil.e(TAG, "Failed to initialize SecurityManager: " + e.getMessage());
        }
    }

    /**
     * Initialize demo encryption key
     * WARNING: In production, implement proper key exchange protocol
     */
    private void initializeDemoKey() {
        try {
            // Generate a consistent demo key from a seed
            // This ensures all devices use the same key for demo purposes
            String seed = "DT_MESSAGING_DEMO_SEED_2024";
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] keyBytes = digest.digest(seed.getBytes(StandardCharsets.UTF_8));

            encryptionKey = new SecretKeySpec(keyBytes, KEY_ALGORITHM);
            LogUtil.d(TAG, "Demo encryption key initialized");

        } catch (Exception e) {
            LogUtil.e(TAG, "Failed to initialize demo key: " + e.getMessage());
        }
    }

    /**
     * Encrypt message content using AES-256-CBC
     */
    public EncryptedMessage encryptMessage(String plaintext) {
        try {
            if (encryptionKey == null) {
                LogUtil.e(TAG, "Encryption key not available");
                return null;
            }

            // Generate random IV
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);
            IvParameterSpec ivSpec = new IvParameterSpec(iv);

            // Initialize cipher for encryption
            Cipher cipher = Cipher.getInstance(AES_TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, ivSpec);

            // Encrypt the plaintext
            byte[] plaintextBytes = plaintext.getBytes(StandardCharsets.UTF_8);
            byte[] ciphertextBytes = cipher.doFinal(plaintextBytes);

            // Encode to Base64 for safe transmission
            String ciphertext = Base64.encodeToString(ciphertextBytes, Base64.NO_WRAP);
            String ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP);

            // Calculate HMAC for integrity
            String hmac = calculateHMAC(plaintext, iv);

            LogUtil.d(TAG, "Message encrypted successfully (" + ciphertextBytes.length + " bytes)");

            return new EncryptedMessage(ciphertext, ivBase64, hmac);

        } catch (Exception e) {
            LogUtil.e(TAG, "Encryption failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Decrypt message content using AES-256-CBC
     */
    public String decryptMessage(EncryptedMessage encryptedMessage) {
        try {
            if (encryptionKey == null) {
                LogUtil.e(TAG, "Encryption key not available");
                return null;
            }

            // Decode Base64
            byte[] ciphertextBytes = Base64.decode(encryptedMessage.ciphertext, Base64.NO_WRAP);
            byte[] iv = Base64.decode(encryptedMessage.iv, Base64.NO_WRAP);
            IvParameterSpec ivSpec = new IvParameterSpec(iv);

            // Initialize cipher for decryption
            Cipher cipher = Cipher.getInstance(AES_TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey, ivSpec);

            // Decrypt the ciphertext
            byte[] plaintextBytes = cipher.doFinal(ciphertextBytes);
            String plaintext = new String(plaintextBytes, StandardCharsets.UTF_8);

            // Verify HMAC for integrity
            String expectedHmac = calculateHMAC(plaintext, iv);
            if (!expectedHmac.equals(encryptedMessage.hmac)) {
                LogUtil.e(TAG, "HMAC verification failed - message may be tampered");
                return null;
            }

            LogUtil.d(TAG, "Message decrypted successfully");
            return plaintext;

        } catch (Exception e) {
            LogUtil.e(TAG, "Decryption failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Calculate HMAC-SHA256 for message integrity
     */
    private String calculateHMAC(String message, byte[] iv) {
        try {
            // Use key + IV as HMAC key for simplicity (in production, use separate HMAC key)
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(encryptionKey.getEncoded());
            digest.update(iv);
            digest.update(message.getBytes(StandardCharsets.UTF_8));

            byte[] hmacBytes = digest.digest();
            return Base64.encodeToString(hmacBytes, Base64.NO_WRAP);

        } catch (Exception e) {
            LogUtil.e(TAG, "HMAC calculation failed: " + e.getMessage());
            return "";
        }
    }

    /**
     * Generate message signature for authenticity
     */
    public String generateMessageSignature(String messageContent, String senderId, long timestamp) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            // Combine message data for signing
            String signatureData = messageContent + senderId + timestamp + System.currentTimeMillis();
            digest.update(signatureData.getBytes(StandardCharsets.UTF_8));

            // Add key for authentication
            if (encryptionKey != null) {
                digest.update(encryptionKey.getEncoded());
            }

            byte[] signatureBytes = digest.digest();
            String signature = Base64.encodeToString(signatureBytes, Base64.NO_WRAP);

            LogUtil.d(TAG, "Message signature generated");
            return signature;

        } catch (Exception e) {
            LogUtil.e(TAG, "Signature generation failed: " + e.getMessage());
            return "";
        }
    }

    /**
     * Verify message signature
     */
    public boolean verifyMessageSignature(String messageContent, String senderId, long timestamp, String signature) {
        try {
            // Note: This is a simplified verification for demo purposes
            // In production, implement proper digital signatures with public/private keys

            if (signature == null || signature.isEmpty()) {
                LogUtil.w(TAG, "No signature provided for verification");
                return false;
            }

            // For demo, we'll just verify the signature format is valid Base64
            byte[] signatureBytes = Base64.decode(signature, Base64.NO_WRAP);
            boolean isValid = signatureBytes.length == 32; // SHA-256 produces 32 bytes

            if (isValid) {
                LogUtil.d(TAG, "Message signature verified");
            } else {
                LogUtil.w(TAG, "Message signature verification failed");
            }

            return isValid;

        } catch (Exception e) {
            LogUtil.e(TAG, "Signature verification failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Generate secure random message ID
     */
    public String generateSecureMessageId() {
        try {
            byte[] randomBytes = new byte[16];
            secureRandom.nextBytes(randomBytes);
            return Base64.encodeToString(randomBytes, Base64.NO_WRAP | Base64.URL_SAFE);
        } catch (Exception e) {
            LogUtil.e(TAG, "Failed to generate secure message ID: " + e.getMessage());
            return "msg_" + System.currentTimeMillis();
        }
    }

    /**
     * Check if security is properly initialized
     */
    public boolean isSecurityReady() {
        return encryptionKey != null && secureRandom != null;
    }

    /**
     * Data class for encrypted message
     */
    public static class EncryptedMessage {
        public final String ciphertext;
        public final String iv;
        public final String hmac;

        public EncryptedMessage(String ciphertext, String iv, String hmac) {
            this.ciphertext = ciphertext;
            this.iv = iv;
            this.hmac = hmac;
        }

        /**
         * Serialize for transmission (JSON format)
         */
        public String serialize() {
            return "{\"c\":\"" + ciphertext + "\",\"i\":\"" + iv + "\",\"h\":\"" + hmac + "\"}";
        }

        /**
         * Deserialize from transmission
         */
        public static EncryptedMessage deserialize(String json) {
            try {
                // Simple JSON parsing for demo (in production, use proper JSON parser)
                String ciphertext = extractJsonValue(json, "c");
                String iv = extractJsonValue(json, "i");
                String hmac = extractJsonValue(json, "h");

                if (ciphertext != null && iv != null && hmac != null) {
                    return new EncryptedMessage(ciphertext, iv, hmac);
                }

                return null;
            } catch (Exception e) {
                LogUtil.e("EncryptedMessage", "Deserialization failed: " + e.getMessage());
                return null;
            }
        }

        private static String extractJsonValue(String json, String key) {
            String keyPattern = "\"" + key + "\":\"";
            int startIndex = json.indexOf(keyPattern);
            if (startIndex == -1) return null;

            startIndex += keyPattern.length();
            int endIndex = json.indexOf("\"", startIndex);
            if (endIndex == -1) return null;

            return json.substring(startIndex, endIndex);
        }
    }

    /**
     * Production warning reminder
     */
    static {
        LogUtil.w("SecurityManager",
                "WARNING: Using demo keys and simplified crypto. " +
                        "Implement proper key exchange and PKI for production use!");
    }
}