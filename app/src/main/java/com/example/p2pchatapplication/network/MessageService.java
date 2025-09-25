package com.example.p2pchatapplication.network;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;


import com.example.p2pchatapplication.R;
import com.example.p2pchatapplication.data.database.MessageEntity;
import com.example.p2pchatapplication.data.repository.MessageRepository;
import com.example.p2pchatapplication.network.protocol.MessageProtocol;
import com.example.p2pchatapplication.ui.MainActivity;
import com.example.p2pchatapplication.utils.Constants;
import com.example.p2pchatapplication.utils.LogUtil;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Background service for managing peer-to-peer connections and message forwarding.
 * Runs as a foreground service to maintain connections when app is minimized.
 */
public abstract class MessageService extends Service implements
        NearbyConnectionManager.ConnectionCallback,
        MessageForwarder.ForwardingCallback {

    private static final String TAG = "MessageService";
    private static final int FOREGROUND_NOTIFICATION_ID = 1001;

    // Service components
    private MessageRepository repository;
    private NearbyConnectionManager connectionManager;
    private MessageForwarder messageForwarder;
    private String currentUserId;

    // Scheduled tasks
    private ScheduledExecutorService scheduledExecutor;

    // Binder for activity communication
    private final MessageServiceBinder binder = new MessageServiceBinder();

    // Service callbacks for UI updates
    public interface ServiceCallback {
        void onPeerConnected(String peerId, String peerName);
        void onPeerDisconnected(String peerId);
        void onMessageReceived(String messageId, String senderId);
        void onConnectionStatusChanged(boolean isConnected, int peerCount);
    }

    private ServiceCallback serviceCallback;

    public class MessageServiceBinder extends Binder {
        public MessageService getService() {
            return MessageService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        LogUtil.d(TAG, "MessageService created");

        // Generate user ID for this device
        currentUserId = "user_" + android.provider.Settings.Secure.getString(
                getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);

        // Initialize components
        repository = new MessageRepository(getApplication());
        connectionManager = new NearbyConnectionManager(this, currentUserId);
        messageForwarder = new MessageForwarder(repository, connectionManager, currentUserId);

        // Set callbacks
        connectionManager.setCallback(this);
        messageForwarder.setCallback(this);

        // Initialize scheduled tasks
        scheduledExecutor = Executors.newScheduledThreadPool(2);

        // Start foreground service
        startForeground(FOREGROUND_NOTIFICATION_ID, createNotification());

        // Start networking
        startNetworking();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        LogUtil.d(TAG, "MessageService started");
        return START_STICKY; // Restart if killed by system
    }

    @Override
    public IBinder onBind(Intent intent) {
        LogUtil.d(TAG, "MessageService bound");
        return binder;
    }

    @Override
    public void onDestroy() {
        LogUtil.d(TAG, "MessageService destroyed");
        stopNetworking();

        if (scheduledExecutor != null) {
            scheduledExecutor.shutdown();
        }

        if (messageForwarder != null) {
            messageForwarder.shutdown();
        }

        super.onDestroy();
    }

    /**
     * Set callback for UI updates
     */
    public void setServiceCallback(ServiceCallback callback) {
        this.serviceCallback = callback;
    }

    /**
     * Start networking components
     */
    private void startNetworking() {
        LogUtil.d(TAG, "Starting networking components");

        // Start advertising and discovery
        connectionManager.startAdvertising();
        connectionManager.startDiscovery();

        // Schedule periodic tasks
        schedulePeriodicTasks();
    }

    /**
     * Stop networking components
     */
    private void stopNetworking() {
        LogUtil.d(TAG, "Stopping networking components");

        if (connectionManager != null) {
            connectionManager.stopAllConnections();
        }

        if (scheduledExecutor != null) {
            scheduledExecutor.shutdown();
        }
    }

    /**
     * Schedule periodic maintenance tasks
     */
    private void schedulePeriodicTasks() {
        // Clean up expired messages every 5 minutes
        scheduledExecutor.scheduleAtFixedRate(() -> {
            messageForwarder.cleanup();
        }, 5, 5, TimeUnit.MINUTES);

        // Log statistics every 2 minutes
        scheduledExecutor.scheduleAtFixedRate(() -> {
            messageForwarder.logStatistics();
        }, 2, 2, TimeUnit.MINUTES);

        // Try to reconnect if no peers every 30 seconds
        scheduledExecutor.scheduleAtFixedRate(() -> {
            if (!connectionManager.isConnectedToAnyPeer()) {
                LogUtil.d(TAG, "No peers connected - attempting rediscovery");
                connectionManager.startDiscovery();
            }
        }, 30, 30, TimeUnit.SECONDS);
    }

    /**
     * Send a new message through the network
     */
    public void sendMessage(MessageEntity messageEntity) {
        if (messageForwarder != null) {
            messageForwarder.sendMessage(messageEntity);
        }
    }

    /**
     * Get current connection status
     */
    public boolean isConnectedToAnyPeer() {
        return connectionManager != null && connectionManager.isConnectedToAnyPeer();
    }

    /**
     * Get connected peer count
     */
    public int getConnectedPeerCount() {
        return connectionManager != null ? connectionManager.getConnectedPeerCount() : 0;
    }

    // NearbyConnectionManager.ConnectionCallback implementations

    @Override
    public void onEndpointDiscovered(String endpointId, String endpointName) {
        LogUtil.d(TAG, "Endpoint discovered: " + endpointName);
        updateNotification("Discovered: " + endpointName);
    }

    @Override
    public void onEndpointConnected(String endpointId, String endpointName) {
        LogUtil.d(TAG, "Peer connected: " + endpointName + " (" + endpointId + ")");

        // Update notification
        updateNotification("Connected to " + getConnectedPeerCount() + " peers");

        // Process pending messages when new peer connects
        messageForwarder.processPendingMessages();

        // Notify UI
        if (serviceCallback != null) {
            serviceCallback.onPeerConnected(endpointId, endpointName);
            serviceCallback.onConnectionStatusChanged(true, getConnectedPeerCount());
        }
    }

    @Override
    public void onEndpointDisconnected(String endpointId) {
        LogUtil.d(TAG, "Peer disconnected: " + endpointId);

        // Update notification
        int peerCount = getConnectedPeerCount();
        updateNotification(peerCount > 0 ?
                "Connected to " + peerCount + " peers" : "No peers connected");

        // Notify UI
        if (serviceCallback != null) {
            serviceCallback.onPeerDisconnected(endpointId);
            serviceCallback.onConnectionStatusChanged(peerCount > 0, peerCount);
        }
    }

    @Override
    public void onMessageReceived(String fromEndpointId, byte[] message) {
        LogUtil.d(TAG, "Raw message received from: " + fromEndpointId);

        // Deserialize and process message
        MessageProtocol.NetworkMessage networkMessage = MessageProtocol.deserializeMessage(message);
        if (networkMessage != null) {
            messageForwarder.processIncomingMessage(fromEndpointId, networkMessage);
        } else {
            LogUtil.e(TAG, "Failed to deserialize received message");
        }
    }

    @Override
    public void onConnectionFailed(String endpointId, String error) {
        LogUtil.w(TAG, "Connection failed to " + endpointId + ": " + error);
    }

    // MessageForwarder.ForwardingCallback implementations

    @Override
    public void onMessageForwarded(String messageId, int peerCount) {
        LogUtil.d(TAG, "Message forwarded: " + messageId + " to " + peerCount + " peers");
    }

    @Override
    public void onMessageReceived(String messageId, String senderId) {
        LogUtil.d(TAG, "Message received for us: " + messageId + " from " + senderId);

        // Show notification for new message
        showMessageNotification("New message from " + senderId);

        // Notify UI
        if (serviceCallback != null) {
            serviceCallback.onMessageReceived(messageId, senderId);
        }
    }

    @Override
    public void onDuplicateMessageFiltered(String messageId) {
        LogUtil.d(TAG, "Duplicate message filtered: " + messageId);
    }

    /**
     * Create foreground service notification
     */
    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0,
                notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL_ID)
                .setContentTitle("DT-Messaging Service")
                .setContentText("Managing peer connections")
                .setSmallIcon(R.drawable.ic_send)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    /**
     * Update foreground notification
     */
    private void updateNotification(String text) {
        Notification notification = new NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL_ID)
                .setContentTitle("DT-Messaging Service")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_send)
                .setOngoing(true)
                .build();

        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(FOREGROUND_NOTIFICATION_ID, notification);
        }
    }

    /**
     * Show notification for new message
     */
    private void showMessageNotification(String text) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0,
                intent, PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL_ID)
                .setContentTitle("New Message")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_send)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build();

        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify((int) System.currentTimeMillis(), notification);
        }
    }
}