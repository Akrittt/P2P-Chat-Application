package com.example.p2pchatapplication.ui;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
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
public class MainActivity extends AppCompatActivity {

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
                connectionStatus.setText("Pending: " + pendingCount + " messages");
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

        // Clear input
        messageInput.setText("");

        // Show feedback
        Toast.makeText(this, "Message queued for delivery", Toast.LENGTH_SHORT).show();
        LogUtil.d(TAG, "Created new message: " + messageId);

        // TODO: Trigger network sending in Phase 3
    }

    /**
     * Request required permissions
     */
    private void requestPermissions() {
        String[] permissions = PermissionHelper.getRequiredPermissions();
        ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE);
    }

    /**
     * Initialize networking components (will be implemented in Phase 3)
     */
    private void initializeNetworking() {
        LogUtil.d(TAG, "Initializing networking components...");
        connectionStatus.setText("Initializing...");

        // TODO: Initialize Nearby Connections or Wi-Fi Direct in Phase 3

        // For now, just update status
        connectionStatus.setText("Ready for connections");
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
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        LogUtil.d(TAG, "MainActivity destroyed");
        // TODO: Cleanup networking resources in Phase 3
    }
}