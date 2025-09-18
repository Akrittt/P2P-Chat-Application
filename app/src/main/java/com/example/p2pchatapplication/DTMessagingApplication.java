package com.example.p2pchatapplication;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;

import com.example.p2pchatapplication.utils.Constants;
import com.example.p2pchatapplication.utils.LogUtil;


/**
 * Custom Application class for DT-Messaging
 * Handles app-wide initialization
 */
public class DTMessagingApplication extends Application {

    private static final String TAG = "DTApplication";

    @Override
    public void onCreate() {
        super.onCreate();

        LogUtil.d(TAG, "Application started");

        // Create notification channel for Android 8.0+
        createNotificationChannel();

        // Initialize other app-wide components here if needed
    }

    /**
     * Create notification channel for foreground service and message notifications
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    Constants.NOTIFICATION_CHANNEL_ID,
                    "DT-Messaging Notifications",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            channel.setDescription("Notifications for delay-tolerant messaging");

            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
                LogUtil.d(TAG, "Notification channel created");
            }
        }
    }
}