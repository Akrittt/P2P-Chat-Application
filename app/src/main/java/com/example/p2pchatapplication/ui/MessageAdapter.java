package com.example.p2pchatapplication.ui;

import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;


import com.example.p2pchatapplication.data.database.MessageEntity;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * RecyclerView Adapter for displaying messages in chat interface
 * Handles both sent and received messages with different layouts
 */
public class MessageAdapter extends RecyclerView.Adapter<MessageAdapter.MessageViewHolder> {

    private List<MessageEntity> messages;
    private String currentUserId;
    private SimpleDateFormat timeFormat;

    public MessageAdapter(List<MessageEntity> messages, String currentUserId) {
        this.messages = messages;
        this.currentUserId = currentUserId;
        this.timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
    }

    @NonNull
    @Override
    public MessageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_message, parent, false);
        return new MessageViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull MessageViewHolder holder, int position) {
        MessageEntity message = messages.get(position);
        holder.bind(message, currentUserId, timeFormat);
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    /**
     * Update messages list and notify adapter
     */
    public void updateMessages(List<MessageEntity> newMessages) {
        this.messages = newMessages;
        notifyDataSetChanged();
    }

    /**
     * ViewHolder class for message items
     */
    static class MessageViewHolder extends RecyclerView.ViewHolder {

        private LinearLayout messageContainer;
        private TextView messageContent;
        private TextView messageTime;
        private TextView messageStatus;
        private TextView messageSender;
        private TextView messageHops;

        public MessageViewHolder(@NonNull View itemView) {
            super(itemView);
            messageContainer = itemView.findViewById(R.id.message_container);
            messageContent = itemView.findViewById(R.id.message_content);
            messageTime = itemView.findViewById(R.id.message_time);
            messageStatus = itemView.findViewById(R.id.message_status);
            messageSender = itemView.findViewById(R.id.message_sender);
            messageHops = itemView.findViewById(R.id.message_hops);
        }

        public void bind(MessageEntity message, String currentUserId, SimpleDateFormat timeFormat) {
            // Set message content
            messageContent.setText(message.content);

            // Set timestamp
            String timeString = timeFormat.format(new Date(message.timestamp));
            messageTime.setText(timeString);

            // Determine if this is an outgoing or incoming message
            boolean isOutgoing = message.senderId.equals(currentUserId);

            // Set layout based on message direction
            LinearLayout.LayoutParams params =
                    (LinearLayout.LayoutParams) messageContainer.getLayoutParams();

            if (isOutgoing) {
                // Outgoing message - align right, blue background
                params.gravity = Gravity.END;
                messageContainer.setBackgroundResource(R.drawable.message_sent_background);
                messageSender.setVisibility(View.GONE);
            } else {
                // Incoming message - align left, gray background
                params.gravity = Gravity.START;
                messageContainer.setBackgroundResource(R.drawable.message_received_background);
                messageSender.setVisibility(View.VISIBLE);
                messageSender.setText("From: " + message.senderId);
            }

            messageContainer.setLayoutParams(params);

            // Set status for outgoing messages
            if (isOutgoing) {
                String statusText = getStatusText(message.status);
                messageStatus.setText(statusText);
                messageStatus.setVisibility(View.VISIBLE);
            } else {
                messageStatus.setVisibility(View.GONE);
            }

            // Show hop count for multi-hop messages
            if (message.hopCount > 0) {
                messageHops.setText("Hops: " + message.hopCount);
                messageHops.setVisibility(View.VISIBLE);
            } else {
                messageHops.setVisibility(View.GONE);
            }
        }

        private String getStatusText(int status) {
            switch (status) {
                case MessageEntity.STATUS_PENDING:
                    return "Pending";
                case MessageEntity.STATUS_SENT:
                    return "Sent";
                case MessageEntity.STATUS_DELIVERED:
                    return "Delivered";
                case MessageEntity.STATUS_FAILED:
                    return "Failed";
                default:
                    return "Unknown";
            }
        }
    }
}