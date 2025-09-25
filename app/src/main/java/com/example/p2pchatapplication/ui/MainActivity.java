package com.example.p2pchatapplication.ui;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.p2pchatapplication.R;
import com.example.p2pchatapplication.data.database.MessageEntity;
import com.example.p2pchatapplication.network.MessageService;
import com.example.p2pchatapplication.ui.viewmodels.ChatViewModel;
import com.example.p2pchatapplication.utils.LogUtil;
import com.example.p2pchatapplication.utils.PermissionHelper;
import com.google.android.material.floatingactionbutton.FloatingActionButton;


import java.util.ArrayList;
import java.util.UUID;

/**
 * Main Activity - Chat Interface
 * Displays messages and handles user input for sending messages
 */
public class MainActivity extends AppCompatActivity implements MessageService.ServiceCallback {

    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQUEST_CODE = 1001;

    // UI Components
    private RecyclerView messagesRecyclerView;
    private EditText messageInput;
    private FloatingActionButton sendButton;
    private TextView statusText;
    private TextView connectionStatus;

    // ViewModel and Adapter
    private ChatViewModel chatViewModel;
    private MessageAdapter messageAdapter;

    // Service connection
    private MessageService messageService;
    private boolean isServiceBound = false;

    // Current user ID (in real app, this would come from user authentication)
    private String currentUserId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Generate unique user ID for this device
        currentUserId = "user_" + android.provider.Settings.Secure.getString(
                getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);

        LogUtil.d(TAG, "Started with user ID: " + currentUserId);

        initializeViews();
        setupRecyclerView();
        setupViewModel();
        setupClickListeners();

        // Check and request permissions
        if (!PermissionHelper.hasRequiredPermissions(this)) {
            requestPermissions();
        } else {
            initializeNetworking();
        }
    }

    /**
     * Initialize UI components
     */
    private void initializeViews() {
        messagesRecyclerView = findViewById(R.id.messages_recycler_view);
        messageInput = findViewById(R.id.message_input);
        sendButton = findViewById(R.id.send_button);
        statusText = findViewById(R.id.status_text);
        connectionStatus = findViewById(R.id.connection_status);

        // Set initial status
        statusText.setText("DT-Messaging Ready");
        connectionStatus.setText("Disconnected");
    }

    /**
     * Setup RecyclerView for messages
     */
    private void setupRecyclerView() {
        messageAdapter = new MessageAdapter(new ArrayList<>(), currentUserId);

        // Use LinearLayoutManager with stackFromEnd=true to show newest messages at bottom
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        layoutManager.setStackFromEnd(true); // Start from bottom

        messagesRecyclerView.setLayoutManager(layoutManager);
        messagesRecyclerView.setAdapter(messageAdapter);

        // Scroll to bottom when new messages are added
        messageAdapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override
            public void onItemRangeInserted(int positionStart, int itemCount) {
                super.onItemRangeInserted(positionStart, itemCount);
                // Smooth scroll to the last item (newest message)
                messagesRecyclerView.smoothScrollToPosition(messageAdapter.getItemCount() - 1);
            }

            @Override
            public void onChanged() {
                super.onChanged();
                // Scroll to bottom when data changes
                if (messageAdapter.getItemCount() > 0) {
                    messagesRecyclerView.scrollToPosition(messageAdapter.getItemCount() - 1);
                }
            }
        });
    }

    /**
     * Setup ViewModel and observe data changes
     */
    private void setupViewModel() {
        chatViewModel = new ViewModelProvider(this).get(ChatViewModel.class);

        // Observe all messages
        chatViewModel.getAllMessages().observe(this, messages -> {
            if (messages != null) {
                messageAdapter.updateMessages(messages);
                LogUtil.d(TAG, "Updated UI with " + messages.size() + " messages");
            }
        });

        // Observe message count for status updates
        chatViewModel.getMessageCount().observe(this, count -> {
            if (count != null) {
                statusText.setText("Total Messages: " + count);
            }
        });

        // Observe pending message count
        chatViewModel.getPendingMessageCount().observe(this, pendingCount -> {
            if (pendingCount != null && pendingCount > 0) {
                String currentStatus = connectionStatus.getText().toString();
                if (!currentStatus.contains("Pending")) {
                    connectionStatus.setText(currentStatus + " | Pending: " + pendingCount);
                }
            }
        });
    }

    /**
     * Setup click listeners for UI interactions
     */
    private void setupClickListeners() {
        sendButton.setOnClickListener(v -> sendMessage());

        // Send message on Enter key press
        messageInput.setOnEditorActionListener((v, actionId, event) -> {
            sendMessage();
            return true;
        });
    }

    /**
     * Send a new message
     */
    private void sendMessage() {
        String messageText = messageInput.getText().toString().trim();

        if (TextUtils.isEmpty(messageText)) {
            Toast.makeText(this, "Please enter a message", Toast.LENGTH_SHORT).show();
            return;
        }

        // For demo purposes, we'll send to a broadcast recipient
        // In real implementation, user would select recipient
        String recipientId = "broadcast"; // Special ID for broadcast messages

        // Create new message
        String messageId = UUID.randomUUID().toString();
        long timestamp = System.currentTimeMillis();
        long ttl = timestamp + (MessageEntity.DEFAULT_TTL_MINUTES * 60 * 1000);

        MessageEntity newMessage = new MessageEntity(
                messageId,
                messageText,
                currentUserId,
                recipientId,
                timestamp,
                MessageEntity.STATUS_PENDING,
                0, // Initial hop count
                ttl,
                true // Outgoing message
        );

        // Insert message through ViewModel
        chatViewModel.insertMessage(newMessage);

        // Send through network service
        if (isServiceBound && messageService != null) {
            messageService.sendMessage(newMessage);

            if (messageService.isConnectedToAnyPeer()) {
                Toast.makeText(this, "Message sent to " +
                        messageService.getConnectedPeerCount() + " peers", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Message queued - no peers connected", Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(this, "Network service not ready", Toast.LENGTH_SHORT).show();
        }

        // Clear input
        messageInput.setText("");

        LogUtil.d(TAG, "Created new message: " + messageId);
    }

    /**
     * Request required permissions
     */
    private void requestPermissions() {
        String[] permissions = PermissionHelper.getRequiredPermissions();
        ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE);
    }

    /**
     * Initialize networking components
     */
    private void initializeNetworking() {
        LogUtil.d(TAG, "Initializing networking components...");
        connectionStatus.setText("Initializing...");

        // Start and bind to MessageService
        Intent serviceIntent = new Intent(this, MessageService.class);
        startService(serviceIntent);
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    // Service connection for MessageService
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            MessageService.MessageServiceBinder binder = (MessageService.MessageServiceBinder) service;
            messageService = binder.getService();
            messageService.setServiceCallback(MainActivity.this);
            isServiceBound = true;

            LogUtil.d(TAG, "MessageService connected");
            connectionStatus.setText("Ready for connections");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            messageService = null;
            isServiceBound = false;
            connectionStatus.setText("Service disconnected");
            LogUtil.d(TAG, "MessageService disconnected");
        }
    };

    // MessageService.ServiceCallback implementations

    @Override
    public void onPeerConnected(String peerId, String peerName) {
        runOnUiThread(() -> {
            Toast.makeText(this, "Connected to: " + peerName, Toast.LENGTH_SHORT).show();
            LogUtil.d(TAG, "UI notified: Peer connected - " + peerName);
        });
    }

    @Override
    public void onPeerDisconnected(String peerId) {
        runOnUiThread(() -> {
            Toast.makeText(this, "Peer disconnected", Toast.LENGTH_SHORT).show();
            LogUtil.d(TAG, "UI notified: Peer disconnected - " + peerId);
        });
    }

    @Override
    public void onMessageReceived(String messageId, String senderId) {
        runOnUiThread(() -> {
            Toast.makeText(this, "New message from " + senderId, Toast.LENGTH_SHORT).show();
            LogUtil.d(TAG, "UI notified: Message received - " + messageId);
        });
    }

    @Override
    public void onConnectionStatusChanged(boolean isConnected, int peerCount) {
        runOnUiThread(() -> {
            if (isConnected && peerCount > 0) {
                connectionStatus.setText("Connected to " + peerCount + " peers");
            } else {
                connectionStatus.setText("No peers connected");
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allPermissionsGranted = true;

            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allPermissionsGranted = false;
                    break;
                }
            }

            if (allPermissionsGranted) {
                LogUtil.d(TAG, "All permissions granted");
                initializeNetworking();
            } else {
                LogUtil.w(TAG, "Some permissions were denied");
                Toast.makeText(this,
                        "Permissions required for peer-to-peer messaging",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Clean up expired messages when app resumes
        chatViewModel.cleanupExpiredMessages();

        // Update connection status if service is bound
        if (isServiceBound && messageService != null) {
            int peerCount = messageService.getConnectedPeerCount();
            if (peerCount > 0) {
                connectionStatus.setText("Connected to " + peerCount + " peers");
            } else {
                connectionStatus.setText("No peers connected");
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Service continues running in background for store-and-forward
        LogUtil.d(TAG, "MainActivity paused - service continues in background");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        LogUtil.d(TAG, "MainActivity destroyed");

        // Unbind from service (but don't stop it - let it run in background)
        if (isServiceBound) {
            try {
                unbindService(serviceConnection);
                isServiceBound = false;
                LogUtil.d(TAG, "Unbound from MessageService");
            } catch (Exception e) {
                LogUtil.e(TAG, "Error unbinding service: " + e.getMessage());
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        LogUtil.d(TAG, "MainActivity started");
    }

    @Override
    protected void onStop() {
        super.onStop();
        LogUtil.d(TAG, "MainActivity stopped");
        // Note: Service continues running for background message handling
    }
}