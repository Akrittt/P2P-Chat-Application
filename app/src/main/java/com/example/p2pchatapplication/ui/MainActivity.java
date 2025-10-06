package com.example.p2pchatapplication.ui;


import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.text.InputType;
import android.text.TextUtils;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.Button;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.p2pchatapplication.R;
import com.example.p2pchatapplication.data.database.FriendEntity;
import com.example.p2pchatapplication.data.database.MessageEntity;
import com.example.p2pchatapplication.data.repository.FriendRepository;
import com.example.p2pchatapplication.network.MessageService;
import com.example.p2pchatapplication.ui.viewmodels.ChatViewModel;
import com.example.p2pchatapplication.utils.LogUtil;
import com.example.p2pchatapplication.utils.PermissionHelper;
import com.google.android.material.floatingactionbutton.FloatingActionButton;


import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

/**
 * MainActivity with Friends System
 * - Save devices as friends
 * - Send to friends even when offline (queued)
 * - Auto-notify when friends come online
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
    private Button selectRecipientButton;
    private Button viewFriendsButton;
    private TextView selectedRecipientText;

    // ViewModels and Repositories

    private ChatViewModel chatViewModel;
    private FriendRepository friendRepository;
    private MessageAdapter messageAdapter;

    // Service
    private MessageService messageService;
    private boolean isServiceBound = false;

    // User and Peers
    private String currentUserId;
    private Map<String, String> discoveredPeers = new HashMap<>();
    private String selectedRecipientId = "broadcast";
    private String selectedRecipientName = "Everyone (Broadcast)";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        currentUserId = "user_" + android.provider.Settings.Secure.getString(
                getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);

        LogUtil.d(TAG, "Started with user ID: " + currentUserId);

        initializeViews();
        setupRecyclerView();
        setupViewModel();
        setupClickListeners();

        if (!PermissionHelper.hasRequiredPermissions(this)) {
            requestPermissions();
        } else {
            initializeNetworking();
        }
    }

    private void initializeViews() {
        messagesRecyclerView = findViewById(R.id.messages_recycler_view);
        messageInput = findViewById(R.id.message_input);
        sendButton = findViewById(R.id.send_button);
        statusText = findViewById(R.id.status_text);
        connectionStatus = findViewById(R.id.connection_status);
        selectRecipientButton = findViewById(R.id.select_recipient_button);
        viewFriendsButton = findViewById(R.id.view_friends_button);
        selectedRecipientText = findViewById(R.id.selected_recipient_text);

        statusText.setText("DT-Messaging Ready");
        connectionStatus.setText("Disconnected");
        selectedRecipientText.setText("To: Everyone (Broadcast)");
    }

    private void setupRecyclerView() {
        messageAdapter = new MessageAdapter(new ArrayList<>(), currentUserId);
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        layoutManager.setStackFromEnd(true);
        messagesRecyclerView.setLayoutManager(layoutManager);
        messagesRecyclerView.setAdapter(messageAdapter);

        messageAdapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override
            public void onItemRangeInserted(int positionStart, int itemCount) {
                super.onItemRangeInserted(positionStart, itemCount);
                messagesRecyclerView.smoothScrollToPosition(messageAdapter.getItemCount() - 1);
            }
        });
    }

    private void setupViewModel() {
        chatViewModel = new ViewModelProvider(this).get(ChatViewModel.class);
        friendRepository = new FriendRepository(getApplication());

        chatViewModel.getAllMessages().observe(this, messages -> {
            if (messages != null) {
                messageAdapter.updateMessages(messages);
            }
        });

        chatViewModel.getMessageCount().observe(this, count -> {
            if (count != null) {
                statusText.setText("Messages: " + count);
            }
        });

        // Observe friend count
        friendRepository.getFriendCount().observe(this, count -> {
            if (count != null && count > 0) {
                viewFriendsButton.setText("Friends (" + count + ")");
            } else {
                viewFriendsButton.setText("Friends");
            }
        });
    }

    private void setupClickListeners() {
        sendButton.setOnClickListener(v -> sendMessage());
        selectRecipientButton.setOnClickListener(v -> showRecipientSelectionDialog());
        viewFriendsButton.setOnClickListener(v -> showFriendsListDialog());
    }

    /**
     * Show recipient selection dialog (Broadcast + Online Peers + Saved Friends)
     */
    private void showRecipientSelectionDialog() {
        ArrayList<String> options = new ArrayList<>();
        ArrayList<String> optionIds = new ArrayList<>();
        ArrayList<String> optionTypes = new ArrayList<>();

        // Add broadcast
        options.add("📢 Everyone (Broadcast)");
        optionIds.add("broadcast");
        optionTypes.add("broadcast");

        // Add online peers (not saved as friends)
        for (Map.Entry<String, String> entry : discoveredPeers.entrySet()) {
            try {
                boolean isFriend = friendRepository.isFriend(entry.getValue()).get();
                if (!isFriend) {
                    options.add("🟢 " + entry.getValue() + " (Online)");
                    optionIds.add(entry.getValue());
                    optionTypes.add("online");
                }
            } catch (Exception e) {
                LogUtil.e(TAG, "Error checking friend status: " + e.getMessage());
            }
        }

        // Add saved friends
        try {
            List<FriendEntity> friends = friendRepository.getAllFriendsList().get();
            if (friends != null) {
                for (FriendEntity friend : friends) {
                    String prefix = friend.isOnline ? "🟢" : "⚪";
                    String status = friend.isOnline ? "Online" : "Offline";
                    options.add(prefix + " " + friend.getDisplayName() + " (" + status + ")");
                    optionIds.add(friend.userId);
                    optionTypes.add("friend");
                }
            }
        } catch (Exception e) {
            LogUtil.e(TAG, "Error loading friends: " + e.getMessage());
        }

        if (options.size() == 1) {
            Toast.makeText(this, "No peers or friends available", Toast.LENGTH_SHORT).show();
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Send Message To:")
                .setItems(options.toArray(new String[0]), (dialog, which) -> {
                    selectedRecipientId = optionIds.get(which);
                    selectedRecipientName = options.get(which);
                    selectedRecipientText.setText("To: " + selectedRecipientName);

                    Toast.makeText(this, "Selected: " + selectedRecipientName, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Show Friends List with options to manage friends
     */
    private void showFriendsListDialog() {
        try {
            List<FriendEntity> friends = friendRepository.getAllFriendsList().get();

            if (friends == null || friends.isEmpty()) {
                Toast.makeText(this, "No friends yet. Add friends from connected peers!",
                        Toast.LENGTH_LONG).show();
                return;
            }

            ArrayList<String> friendNames = new ArrayList<>();
            for (FriendEntity friend : friends) {
                friendNames.add(friend.getStatusBadge() + " " + friend.getDisplayName() +
                        "\n   " + friend.getLastSeenText() + " • " + friend.totalMessages + " messages");
            }

            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("My Friends (" + friends.size() + ")")
                    .setItems(friendNames.toArray(new String[0]), (dialog, which) -> {
                        showFriendOptionsDialog(friends.get(which));
                    })
                    .setNeutralButton("Add Friend", (dialog, which) -> {
                        showAddFriendDialog();
                    })
                    .setNegativeButton("Close", null)
                    .show();

        } catch (Exception e) {
            LogUtil.e(TAG, "Error showing friends list: " + e.getMessage());
            Toast.makeText(this, "Error loading friends", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Show options for a specific friend
     */
    private void showFriendOptionsDialog(FriendEntity friend) {
        String[] options = {
                "💬 Send Message",
                "✏️ Edit Nickname",
                friend.isFavorite ? "⭐ Remove from Favorites" : "☆ Add to Favorites",
                "📊 View Chat History",
                "🗑️ Remove Friend"
        };

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(friend.getDisplayName())
                .setItems(options, (dialog, which) -> {
                    switch (which) {
                        case 0: // Send Message
                            selectedRecipientId = friend.userId;
                            selectedRecipientName = friend.getDisplayName();
                            selectedRecipientText.setText("To: " + selectedRecipientName);
                            Toast.makeText(this, "Recipient set to: " + friend.getDisplayName(),
                                    Toast.LENGTH_SHORT).show();
                            messageInput.requestFocus();
                            break;

                        case 1: // Edit Nickname
                            showEditNicknameDialog(friend);
                            break;

                        case 2: // Toggle Favorite
                            friendRepository.toggleFavorite(friend.userId, !friend.isFavorite);
                            Toast.makeText(this,
                                    friend.isFavorite ? "Removed from favorites" : "Added to favorites",
                                    Toast.LENGTH_SHORT).show();
                            break;

                        case 3: // View Chat History
                            viewChatHistory(friend);
                            break;

                        case 4: // Remove Friend
                            confirmRemoveFriend(friend);
                            break;
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Show dialog to add a friend from online peers
     */
    private void showAddFriendDialog() {
        if (discoveredPeers.isEmpty()) {
            Toast.makeText(this, "No peers online. Wait for someone to connect!",
                    Toast.LENGTH_LONG).show();
            return;
        }

        // Filter out users who are already friends
        ArrayList<String> availablePeers = new ArrayList<>();
        ArrayList<String> availablePeerIds = new ArrayList<>();

        for (Map.Entry<String, String> entry : discoveredPeers.entrySet()) {
            String userId = entry.getValue();
            try {
                boolean isFriend = friendRepository.isFriend(userId).get();
                if (!isFriend) {
                    availablePeers.add("🟢 " + userId);
                    availablePeerIds.add(userId);
                }
            } catch (Exception e) {
                LogUtil.e(TAG, "Error checking friend status: " + e.getMessage());
            }
        }

        if (availablePeers.isEmpty()) {
            Toast.makeText(this, "All online peers are already your friends!",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Add Friend")
                .setItems(availablePeers.toArray(new String[0]), (dialog, which) -> {
                    String selectedUserId = availablePeerIds.get(which);
                    String endpointId = getEndpointIdForUserId(selectedUserId);
                    showNicknameInputDialog(selectedUserId, endpointId);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Show dialog to input nickname for new friend
     */
    private void showNicknameInputDialog(String userId, String endpointId) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        input.setHint("Enter a nickname (optional)");

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Add Friend")
                .setMessage("Add " + userId + " as friend?")
                .setView(input)
                .setPositiveButton("Add", (dialog, which) -> {
                    String nickname = input.getText().toString().trim();
                    if (nickname.isEmpty()) {
                        nickname = userId; // Use user ID if no nickname provided
                    }

                    FriendEntity newFriend = new FriendEntity(
                            userId,
                            nickname,
                            endpointId,
                            System.currentTimeMillis()
                    );
                    newFriend.isOnline = true;

                    friendRepository.addFriend(newFriend);

                    Toast.makeText(this, "✅ Added " + nickname + " as friend!",
                            Toast.LENGTH_LONG).show();
                    LogUtil.d(TAG, "Added friend: " + nickname + " (" + userId + ")");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Show dialog to edit friend's nickname
     */
    private void showEditNicknameDialog(FriendEntity friend) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        input.setText(friend.nickname);
        input.setHint("Enter new nickname");

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Edit Nickname")
                .setView(input)
                .setPositiveButton("Save", (dialog, which) -> {
                    String newNickname = input.getText().toString().trim();
                    if (!newNickname.isEmpty()) {
                        friend.nickname = newNickname;
                        friendRepository.addFriend(friend); // Update in database
                        Toast.makeText(this, "Nickname updated to: " + newNickname,
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Confirm before removing friend
     */
    private void confirmRemoveFriend(FriendEntity friend) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Remove Friend?")
                .setMessage("Remove " + friend.getDisplayName() + " from your friends list?\n\n" +
                        "You can always add them back later.")
                .setPositiveButton("Remove", (dialog, which) -> {
                    friendRepository.removeFriend(friend);
                    Toast.makeText(this, "Removed " + friend.getDisplayName(),
                            Toast.LENGTH_SHORT).show();

                    // If this was selected recipient, switch to broadcast
                    if (selectedRecipientId.equals(friend.userId)) {
                        selectedRecipientId = "broadcast";
                        selectedRecipientName = "Everyone (Broadcast)";
                        selectedRecipientText.setText("To: Everyone (Broadcast)");
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * View chat history with a friend
     */
    private void viewChatHistory(FriendEntity friend) {
        // Get conversation with this friend
        chatViewModel.getConversation(currentUserId, friend.userId).observe(this, messages -> {
            if (messages != null && !messages.isEmpty()) {
                StringBuilder history = new StringBuilder();
                history.append("Chat with ").append(friend.getDisplayName())
                        .append("\n").append(messages.size()).append(" messages\n\n");

                for (MessageEntity msg : messages) {
                    String sender = msg.isOutgoing ? "You" : friend.getDisplayName();
                    history.append(sender).append(": ").append(msg.content).append("\n\n");
                }

                AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setTitle("Chat History")
                        .setMessage(history.toString())
                        .setPositiveButton("OK", null)
                        .show();
            } else {
                Toast.makeText(this, "No messages with " + friend.getDisplayName() + " yet",
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * Get endpoint ID for a user ID from discovered peers
     */
    private String getEndpointIdForUserId(String userId) {
        for (Map.Entry<String, String> entry : discoveredPeers.entrySet()) {
            if (entry.getValue().equals(userId)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private void sendMessage() {
        String messageText = messageInput.getText().toString().trim();

        if (TextUtils.isEmpty(messageText)) {
            Toast.makeText(this, "Please enter a message", Toast.LENGTH_SHORT).show();
            return;
        }

        String messageId = UUID.randomUUID().toString();
        long timestamp = System.currentTimeMillis();
        long ttl = timestamp + (MessageEntity.DEFAULT_TTL_MINUTES * 60 * 1000);

        MessageEntity newMessage = new MessageEntity(
                messageId,
                messageText,
                currentUserId,
                selectedRecipientId,
                timestamp,
                MessageEntity.STATUS_PENDING,
                0,
                ttl,
                true
        );

        chatViewModel.insertMessage(newMessage);

        // Increment message count if sending to a friend
        if (!selectedRecipientId.equals("broadcast")) {
            friendRepository.incrementMessageCount(selectedRecipientId);
        }

        if (isServiceBound && messageService != null) {
            messageService.sendMessage(newMessage);

            if (messageService.isConnectedToAnyPeer()) {
                String recipientInfo = selectedRecipientId.equals("broadcast") ?
                        "broadcast to " + messageService.getConnectedPeerCount() + " peers" :
                        "sent to " + selectedRecipientName;
                Toast.makeText(this, "Message " + recipientInfo, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Message queued - will send when peer comes online",
                        Toast.LENGTH_LONG).show();
            }
        }

        messageInput.setText("");
        LogUtil.d(TAG, "Created message to " + selectedRecipientName);
    }

    private void requestPermissions() {
        String[] permissions = PermissionHelper.getRequiredPermissions();
        ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE);
    }

    private void initializeNetworking() {
        LogUtil.d(TAG, "Initializing networking...");
        connectionStatus.setText("Initializing...");

        Intent serviceIntent = new Intent(this, MessageService.class);
        startService(serviceIntent);
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

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
        }
    };

    // MessageService.ServiceCallback implementations

    @Override
    public void onPeerConnected(String peerId, String peerName) {
        runOnUiThread(() -> {
            discoveredPeers.put(peerId, peerName);

            // Check if this peer is a saved friend
            try {
                FriendEntity friend = friendRepository.getFriendByUserId(peerName).get();
                if (friend != null) {
                    // Update friend online status
                    friendRepository.updateOnlineStatus(peerName, true);
                    friendRepository.updateEndpointId(peerName, peerId);

                    Toast.makeText(this, "🎉 Friend online: " + friend.getDisplayName(),
                            Toast.LENGTH_LONG).show();
                    LogUtil.d(TAG, "Friend came online: " + friend.getDisplayName());
                } else {
                    Toast.makeText(this, "✅ Connected: " + peerName, Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                LogUtil.e(TAG, "Error checking friend status: " + e.getMessage());
            }

            updateConnectionStatusText();
        });
    }

    @Override
    public void onPeerDisconnected(String peerId) {
        runOnUiThread(() -> {
            String peerName = discoveredPeers.remove(peerId);

            if (peerName != null) {
                // Update friend offline status
                friendRepository.updateOnlineStatus(peerName, false);

                try {
                    FriendEntity friend = friendRepository.getFriendByUserId(peerName).get();
                    if (friend != null) {
                        Toast.makeText(this, "Friend offline: " + friend.getDisplayName(),
                                Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "❌ Disconnected: " + peerName,
                                Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    LogUtil.e(TAG, "Error updating friend status: " + e.getMessage());
                }

                if (selectedRecipientId.equals(peerName)) {
                    Toast.makeText(this, "Recipient went offline. Messages will be queued.",
                            Toast.LENGTH_LONG).show();
                }
            }

            updateConnectionStatusText();
        });
    }

    @Override
    public void onMessageReceived(String messageId, String senderId) {
        runOnUiThread(() -> {
            try {
                FriendEntity friend = friendRepository.getFriendByUserId(senderId).get();
                String senderName = friend != null ? friend.getDisplayName() : senderId;

                Toast.makeText(this, "📨 New message from " + senderName,
                        Toast.LENGTH_SHORT).show();

                // Increment message count for friend
                if (friend != null) {
                    friendRepository.incrementMessageCount(senderId);
                }
            } catch (Exception e) {
                LogUtil.e(TAG, "Error processing received message: " + e.getMessage());
            }
        });
    }

    @Override
    public void onConnectionStatusChanged(boolean isConnected, int peerCount) {
        runOnUiThread(() -> {
            updateConnectionStatusText();
        });
    }

    private void updateConnectionStatusText() {
        int peerCount = discoveredPeers.size();

        if (peerCount > 0) {
            connectionStatus.setText("🟢 Connected: " + peerCount + " peer" +
                    (peerCount > 1 ? "s" : ""));
            selectRecipientButton.setEnabled(true);
        } else {
            connectionStatus.setText("🔴 No peers connected");
            selectRecipientButton.setEnabled(false);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (allGranted) {
                initializeNetworking();
            } else {
                Toast.makeText(this, "Permissions required for messaging",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        chatViewModel.cleanupExpiredMessages();
        if (isServiceBound && messageService != null) {
            updateConnectionStatusText();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isServiceBound) {
            try {
                unbindService(serviceConnection);
                isServiceBound = false;
            } catch (Exception e) {
                LogUtil.e(TAG, "Error unbinding service: " + e.getMessage());
            }
        }
    }
}