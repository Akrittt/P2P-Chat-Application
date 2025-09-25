package com.example.p2pchatapplication.network;

import android.content.Context;
import androidx.annotation.NonNull;

import com.example.p2pchatapplication.utils.Constants;
import com.example.p2pchatapplication.utils.LogUtil;
import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.AdvertisingOptions;
import com.google.android.gms.nearby.connection.ConnectionInfo;
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback;
import com.google.android.gms.nearby.connection.ConnectionResolution;
import com.google.android.gms.nearby.connection.ConnectionsClient;
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo;
import com.google.android.gms.nearby.connection.DiscoveryOptions;
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback;
import com.google.android.gms.nearby.connection.Payload;
import com.google.android.gms.nearby.connection.PayloadCallback;
import com.google.android.gms.nearby.connection.PayloadTransferUpdate;
import com.google.android.gms.nearby.connection.Strategy;



import java.util.HashSet;
import java.util.Set;

/**
 * Manages Google Nearby Connections API for peer-to-peer communication.
 * Handles device discovery, connection management, and message transmission.
 */
public class NearbyConnectionManager {

    private static final String TAG = "NearbyConnectionManager";

    // Nearby Connections client
    private ConnectionsClient connectionsClient;
    private Context context;
    private String localUserName;

    // Connection state
    private Set<String> connectedEndpoints;
    private boolean isAdvertising = false;
    private boolean isDiscovering = false;

    // Callbacks for UI updates
    public interface ConnectionCallback {
        void onEndpointDiscovered(String endpointId, String endpointName);
        void onEndpointConnected(String endpointId, String endpointName);
        void onEndpointDisconnected(String endpointId);
        void onMessageReceived(String fromEndpointId, byte[] message);
        void onConnectionFailed(String endpointId, String error);
    }

    private ConnectionCallback callback;

    public NearbyConnectionManager(Context context, String userName) {
        this.context = context;
        this.localUserName = userName;
        this.connectionsClient = Nearby.getConnectionsClient(context);
        this.connectedEndpoints = new HashSet<>();

        LogUtil.d(TAG, "NearbyConnectionManager initialized for user: " + userName);
    }

    public void setCallback(ConnectionCallback callback) {
        this.callback = callback;
    }

    /**
     * Start advertising this device so others can discover it
     */
    public void startAdvertising() {
        if (isAdvertising) {
            LogUtil.d(TAG, "Already advertising");
            return;
        }

        AdvertisingOptions advertisingOptions = new AdvertisingOptions.Builder()
                .setStrategy(Strategy.P2P_CLUSTER)
                .build();

        connectionsClient
                .startAdvertising(localUserName, Constants.SERVICE_ID, connectionLifecycleCallback, advertisingOptions)
                .addOnSuccessListener(unused -> {
                    isAdvertising = true;
                    LogUtil.d(TAG, "Started advertising as: " + localUserName);
                })
                .addOnFailureListener(exception -> {
                    isAdvertising = false;
                    LogUtil.e(TAG, "Failed to start advertising: " + exception.getMessage());
                });
    }

    /**
     * Start discovering nearby devices
     */
    public void startDiscovery() {
        if (isDiscovering) {
            LogUtil.d(TAG, "Already discovering");
            return;
        }

        DiscoveryOptions discoveryOptions = new DiscoveryOptions.Builder()
                .setStrategy(Strategy.P2P_CLUSTER)
                .build();

        connectionsClient
                .startDiscovery(Constants.SERVICE_ID, endpointDiscoveryCallback, discoveryOptions)
                .addOnSuccessListener(unused -> {
                    isDiscovering = true;
                    LogUtil.d(TAG, "Started discovery");
                })
                .addOnFailureListener(exception -> {
                    isDiscovering = false;
                    LogUtil.e(TAG, "Failed to start discovery: " + exception.getMessage());
                });
    }

    /**
     * Request connection to a discovered endpoint
     */
    public void connectToEndpoint(String endpointId, String endpointName) {
        LogUtil.d(TAG, "Requesting connection to: " + endpointName + " (" + endpointId + ")");

        connectionsClient
                .requestConnection(localUserName, endpointId, connectionLifecycleCallback)
                .addOnSuccessListener(unused -> {
                    LogUtil.d(TAG, "Connection request sent to: " + endpointName);
                })
                .addOnFailureListener(exception -> {
                    LogUtil.e(TAG, "Failed to request connection: " + exception.getMessage());
                    if (callback != null) {
                        callback.onConnectionFailed(endpointId, exception.getMessage());
                    }
                });
    }

    /**
     * Send message to a specific endpoint
     */
    public void sendMessage(String endpointId, byte[] message) {
        if (!connectedEndpoints.contains(endpointId)) {
            LogUtil.w(TAG, "Cannot send message - not connected to: " + endpointId);
            return;
        }

        Payload payload = Payload.fromBytes(message);

        connectionsClient
                .sendPayload(endpointId, payload)
                .addOnSuccessListener(unused -> {
                    LogUtil.d(TAG, "Message sent to: " + endpointId);
                })
                .addOnFailureListener(exception -> {
                    LogUtil.e(TAG, "Failed to send message: " + exception.getMessage());
                });
    }

    /**
     * Broadcast message to all connected endpoints
     */
    public void broadcastMessage(byte[] message) {
        if (connectedEndpoints.isEmpty()) {
            LogUtil.w(TAG, "No connected endpoints for broadcast");
            return;
        }

        Payload payload = Payload.fromBytes(message);

        for (String endpointId : connectedEndpoints) {
            connectionsClient.sendPayload(endpointId, payload);
        }

        LogUtil.d(TAG, "Broadcast message to " + connectedEndpoints.size() + " endpoints");
    }

    /**
     * Disconnect from a specific endpoint
     */
    public void disconnectFromEndpoint(String endpointId) {
        connectionsClient.disconnectFromEndpoint(endpointId);
        connectedEndpoints.remove(endpointId);
        LogUtil.d(TAG, "Disconnected from: " + endpointId);
    }

    /**
     * Stop all connections and cleanup
     */
    public void stopAllConnections() {
        connectionsClient.stopAllEndpoints();
        connectedEndpoints.clear();
        isAdvertising = false;
        isDiscovering = false;
        LogUtil.d(TAG, "Stopped all connections");
    }

    /**
     * Get current connection status
     */
    public boolean isConnectedToAnyPeer() {
        return !connectedEndpoints.isEmpty();
    }

    public int getConnectedPeerCount() {
        return connectedEndpoints.size();
    }

    public Set<String> getConnectedEndpoints() {
        return new HashSet<>(connectedEndpoints);
    }

    // Callback for discovering endpoints
    private final EndpointDiscoveryCallback endpointDiscoveryCallback = new EndpointDiscoveryCallback() {
        @Override
        public void onEndpointFound(@NonNull String endpointId, @NonNull DiscoveredEndpointInfo info) {
            LogUtil.d(TAG, "Endpoint discovered: " + info.getEndpointName() + " (" + endpointId + ")");

            if (callback != null) {
                callback.onEndpointDiscovered(endpointId, info.getEndpointName());
            }

            // Auto-connect to discovered endpoints for demo
            // In production, you might want user approval first
            connectToEndpoint(endpointId, info.getEndpointName());
        }

        @Override
        public void onEndpointLost(@NonNull String endpointId) {
            LogUtil.d(TAG, "Endpoint lost: " + endpointId);
        }
    };

    // Callback for connection lifecycle events
    private final ConnectionLifecycleCallback connectionLifecycleCallback = new ConnectionLifecycleCallback() {
        @Override
        public void onConnectionInitiated(@NonNull String endpointId, @NonNull ConnectionInfo connectionInfo) {
            LogUtil.d(TAG, "Connection initiated with: " + connectionInfo.getEndpointName());

            // Accept the connection automatically for demo
            // In production, you might want user approval
            connectionsClient.acceptConnection(endpointId, payloadCallback);
        }

        @Override
        public void onConnectionResult(@NonNull String endpointId, @NonNull ConnectionResolution result) {
            if (result.getStatus().isSuccess()) {
                LogUtil.d(TAG, "Connection established with: " + endpointId);
                connectedEndpoints.add(endpointId);

                if (callback != null) {
                    callback.onEndpointConnected(endpointId, "Peer_" + endpointId.substring(0, 4));
                }
            } else {
                LogUtil.e(TAG, "Connection failed with: " + endpointId);
                if (callback != null) {
                    callback.onConnectionFailed(endpointId, "Connection rejected or failed");
                }
            }
        }

        @Override
        public void onDisconnected(@NonNull String endpointId) {
            LogUtil.d(TAG, "Disconnected from: " + endpointId);
            connectedEndpoints.remove(endpointId);

            if (callback != null) {
                callback.onEndpointDisconnected(endpointId);
            }
        }
    };

    // Callback for handling received payloads (messages)
    private final PayloadCallback payloadCallback = new PayloadCallback() {
        @Override
        public void onPayloadReceived(@NonNull String endpointId, @NonNull Payload payload) {
            if (payload.getType() == Payload.Type.BYTES) {
                byte[] receivedBytes = payload.asBytes();
                if (receivedBytes != null) {
                    LogUtil.d(TAG, "Message received from: " + endpointId +
                            " (" + receivedBytes.length + " bytes)");

                    if (callback != null) {
                        callback.onMessageReceived(endpointId, receivedBytes);
                    }
                }
            }
        }

        @Override
        public void onPayloadTransferUpdate(@NonNull String endpointId, @NonNull PayloadTransferUpdate update) {
            // Handle transfer progress if needed
            if (update.getStatus() == PayloadTransferUpdate.Status.SUCCESS) {
                LogUtil.d(TAG, "Payload transfer completed with: " + endpointId);
            } else if (update.getStatus() == PayloadTransferUpdate.Status.FAILURE) {
                LogUtil.e(TAG, "Payload transfer failed with: " + endpointId);
            }
        }
    };
}
