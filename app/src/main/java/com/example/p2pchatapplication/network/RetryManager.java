package com.example.p2pchatapplication.network;

import com.example.p2pchatapplication.data.database.MessageEntity;
import com.example.p2pchatapplication.data.repository.MessageRepository;
import com.example.p2pchatapplication.utils.LogUtil;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.Map;

/**
 * Manages retry logic for failed message transmissions.
 * Implements exponential backoff and maximum retry limits.
 */
public class RetryManager {

    private static final String TAG = "RetryManager";

    // Retry configuration
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final long INITIAL_RETRY_DELAY_MS = 5000; // 5 seconds
    private static final double BACKOFF_MULTIPLIER = 2.0;
    private static final long MAX_RETRY_DELAY_MS = 300000; // 5 minutes

    // Dependencies
    private MessageRepository repository;
    private NearbyConnectionManager connectionManager;
    private MessageForwarder messageForwarder;

    // Retry tracking
    private Map<String, RetryInfo> pendingRetries;
    private ScheduledExecutorService retryExecutor;

    // Callback interface
    public interface RetryCallback {
        void onRetryScheduled(String messageId, int attemptNumber, long delayMs);
        void onRetrySucceeded(String messageId, int attemptNumber);
        void onRetryFailed(String messageId, int totalAttempts);
        void onMaxRetriesExceeded(String messageId);
    }

    private RetryCallback callback;

    /**
     * Information about pending retry
     */
    private static class RetryInfo {
        final String messageId;
        final int attemptNumber;
        final long nextRetryTime;
        final ScheduledFuture<?> retryTask;

        RetryInfo(String messageId, int attemptNumber, long nextRetryTime, ScheduledFuture<?> retryTask) {
            this.messageId = messageId;
            this.attemptNumber = attemptNumber;
            this.nextRetryTime = nextRetryTime;
            this.retryTask = retryTask;
        }
    }

    public RetryManager(MessageRepository repository, NearbyConnectionManager connectionManager, MessageForwarder messageForwarder) {
        this.repository = repository;
        this.connectionManager = connectionManager;
        this.messageForwarder = messageForwarder;
        this.pendingRetries = new ConcurrentHashMap<>();
        this.retryExecutor = Executors.newScheduledThreadPool(2);

        LogUtil.d(TAG, "RetryManager initialized");
    }

    public void setCallback(RetryCallback callback) {
        this.callback = callback;
    }

    // ============================================================================
    // HERE IS THE scheduleRetry() METHOD YOU'RE LOOKING FOR!
    // ============================================================================

    /**
     * Schedule retry for a failed message.
     * This is the main method that implements exponential backoff retry logic.
     *
     * @param messageId The ID of the message to retry
     * @param currentAttempt The current attempt number (0 for first retry)
     */
    public void scheduleRetry(String messageId, int currentAttempt) {
        try {
            LogUtil.d(TAG, "scheduleRetry called for message: " + messageId + " (attempt: " + currentAttempt + ")");

            // Check if already at max retries
            if (currentAttempt >= MAX_RETRY_ATTEMPTS) {
                LogUtil.d(TAG, "Max retries exceeded for message: " + messageId);
                markMessageAsFailed(messageId);

                if (callback != null) {
                    callback.onMaxRetriesExceeded(messageId);
                }
                return;
            }

            // Cancel existing retry if any
            cancelRetry(messageId);

            // Calculate delay with exponential backoff
            long delayMs = calculateRetryDelay(currentAttempt);
            long nextRetryTime = System.currentTimeMillis() + delayMs;

            LogUtil.d(TAG, "Scheduling retry for " + messageId + " in " + delayMs + "ms");

            // Schedule retry task
            ScheduledFuture<?> retryTask = retryExecutor.schedule(() -> {
                LogUtil.d(TAG, "Executing scheduled retry for: " + messageId);
                executeRetry(messageId, currentAttempt + 1);
            }, delayMs, TimeUnit.MILLISECONDS);

            // Track retry info
            RetryInfo retryInfo = new RetryInfo(messageId, currentAttempt + 1, nextRetryTime, retryTask);
            pendingRetries.put(messageId, retryInfo);

            LogUtil.d(TAG, "Scheduled retry for message " + messageId +
                    " (attempt " + (currentAttempt + 1) + "/" + MAX_RETRY_ATTEMPTS +
                    " in " + delayMs + "ms)");

            if (callback != null) {
                callback.onRetryScheduled(messageId, currentAttempt + 1, delayMs);
            }

        } catch (Exception e) {
            LogUtil.e(TAG, "Failed to schedule retry: " + e.getMessage());
        }
    }

    // ============================================================================

    /**
     * Calculate retry delay with exponential backoff
     */
    private long calculateRetryDelay(int attemptNumber) {
        long delay = (long) (INITIAL_RETRY_DELAY_MS * Math.pow(BACKOFF_MULTIPLIER, attemptNumber));
        return Math.min(delay, MAX_RETRY_DELAY_MS);
    }

    /**
     * Execute retry attempt
     */
    private void executeRetry(String messageId, int attemptNumber) {
        try {
            LogUtil.d(TAG, "Executing retry for message: " + messageId + " (attempt " + attemptNumber + ")");

            // Remove from pending retries
            pendingRetries.remove(messageId);

            // Check if we have peers to send to
            if (!connectionManager.isConnectedToAnyPeer()) {
                LogUtil.d(TAG, "No peers available for retry, rescheduling: " + messageId);
                scheduleRetry(messageId, attemptNumber);
                return;
            }

            // Get message from database and attempt to resend
            repository.getPendingMessages().thenAccept(pendingMessages -> {
                if (pendingMessages != null) {
                    for (MessageEntity message : pendingMessages) {
                        if (message.messageId.equals(messageId)) {
                            // Attempt to resend
                            boolean success = attemptResend(message);

                            if (success) {
                                LogUtil.d(TAG, "Retry succeeded for message: " + messageId);
                                if (callback != null) {
                                    callback.onRetrySucceeded(messageId, attemptNumber);
                                }
                            } else {
                                LogUtil.d(TAG, "Retry failed for message: " + messageId);
                                if (callback != null) {
                                    callback.onRetryFailed(messageId, attemptNumber);
                                }

                                // Schedule next retry
                                scheduleRetry(messageId, attemptNumber);
                            }
                            return;
                        }
                    }
                }

                LogUtil.w(TAG, "Message not found for retry: " + messageId);
            });

        } catch (Exception e) {
            LogUtil.e(TAG, "Error executing retry: " + e.getMessage());
        }
    }

    /**
     * Attempt to resend message
     */
    private boolean attemptResend(MessageEntity message) {
        try {
            // Check if message is still valid (not expired)
            if (System.currentTimeMillis() > message.ttl) {
                LogUtil.d(TAG, "Message expired, not retrying: " + message.messageId);
                markMessageAsFailed(message.messageId);
                return false;
            }

            // Use MessageForwarder to send
            messageForwarder.sendMessage(message);

            // Consider success if we have connected peers
            boolean hasConnectedPeers = connectionManager.isConnectedToAnyPeer();

            if (hasConnectedPeers) {
                // Update status to sent (optimistic)
                repository.updateMessageStatus(message.messageId, MessageEntity.STATUS_SENT);
                return true;
            }

            return false;

        } catch (Exception e) {
            LogUtil.e(TAG, "Resend attempt failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Cancel pending retry for a message
     */
    public void cancelRetry(String messageId) {
        RetryInfo retryInfo = pendingRetries.remove(messageId);
        if (retryInfo != null && retryInfo.retryTask != null) {
            retryInfo.retryTask.cancel(false);
            LogUtil.d(TAG, "Cancelled retry for message: " + messageId);
        }
    }

    /**
     * Mark message as permanently failed
     */
    private void markMessageAsFailed(String messageId) {
        repository.updateMessageStatus(messageId, MessageEntity.STATUS_FAILED);
        cancelRetry(messageId);
        LogUtil.d(TAG, "Marked message as failed: " + messageId);
    }

    /**
     * Mark message as successfully delivered (cancel retries)
     */
    public void markAsDelivered(String messageId) {
        cancelRetry(messageId);
        repository.updateMessageStatus(messageId, MessageEntity.STATUS_DELIVERED);
        LogUtil.d(TAG, "Message delivered, cancelled retries: " + messageId);
    }

    /**
     * Retry all pending messages when connection is restored
     */
    public void retryPendingMessagesOnConnectionRestored() {
        retryExecutor.execute(() -> {
            try {
                LogUtil.d(TAG, "Connection restored, checking for pending messages to retry");

                repository.getPendingMessages().thenAccept(pendingMessages -> {
                    if (pendingMessages != null) {
                        for (MessageEntity message : pendingMessages) {
                            if (message.isOutgoing && message.status == MessageEntity.STATUS_PENDING) {
                                // Schedule immediate retry for pending outgoing messages
                                scheduleImmediateRetry(message.messageId);
                            }
                        }
                    }
                });

            } catch (Exception e) {
                LogUtil.e(TAG, "Error retrying pending messages: " + e.getMessage());
            }
        });
    }

    /**
     * Schedule immediate retry (bypass delay)
     */
    public void scheduleImmediateRetry(String messageId) {
        retryExecutor.schedule(() -> {
            executeRetry(messageId, 0); // Start with attempt 0
        }, 1000, TimeUnit.MILLISECONDS); // Small delay to allow connection to stabilize

        LogUtil.d(TAG, "Scheduled immediate retry for message: " + messageId);
    }

    /**
     * Get current retry statistics
     */
    public RetryStatistics getRetryStatistics() {
        int totalPendingRetries = pendingRetries.size();
        long oldestRetryTime = 0;

        for (RetryInfo retryInfo : pendingRetries.values()) {
            if (oldestRetryTime == 0 || retryInfo.nextRetryTime < oldestRetryTime) {
                oldestRetryTime = retryInfo.nextRetryTime;
            }
        }

        return new RetryStatistics(totalPendingRetries, oldestRetryTime);
    }

    /**
     * Cleanup expired retry attempts
     */
    public void cleanup() {
        retryExecutor.execute(() -> {
            try {
                long currentTime = System.currentTimeMillis();

                for (Map.Entry<String, RetryInfo> entry : pendingRetries.entrySet()) {
                    String messageId = entry.getKey();
                    RetryInfo retryInfo = entry.getValue();

                    // Check if retry has been pending too long
                    if (currentTime - retryInfo.nextRetryTime > MAX_RETRY_DELAY_MS * 2) {
                        LogUtil.d(TAG, "Cleaning up stale retry for message: " + messageId);
                        cancelRetry(messageId);
                        markMessageAsFailed(messageId);
                    }
                }

                LogUtil.d(TAG, "Retry cleanup completed");

            } catch (Exception e) {
                LogUtil.e(TAG, "Error during retry cleanup: " + e.getMessage());
            }
        });
    }

    /**
     * Shutdown retry manager
     */
    public void shutdown() {
        try {
            // Cancel all pending retries
            for (String messageId : pendingRetries.keySet()) {
                cancelRetry(messageId);
            }

            // Shutdown executor
            retryExecutor.shutdown();
            if (!retryExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                retryExecutor.shutdownNow();
            }

            LogUtil.d(TAG, "RetryManager shutdown completed");

        } catch (Exception e) {
            LogUtil.e(TAG, "Error during shutdown: " + e.getMessage());
        }
    }

    /**
     * Statistics class for monitoring
     */
    public static class RetryStatistics {
        public final int pendingRetries;
        public final long oldestRetryTime;

        public RetryStatistics(int pendingRetries, long oldestRetryTime) {
            this.pendingRetries = pendingRetries;
            this.oldestRetryTime = oldestRetryTime;
        }
    }
}

