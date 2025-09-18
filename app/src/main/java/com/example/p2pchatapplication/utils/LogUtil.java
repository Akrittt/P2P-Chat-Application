package com.example.p2pchatapplication.utils;

import android.util.Log;

/**
 * Logging utility class with consistent tags and formatting
 */
public class LogUtil {
    private static final String APP_TAG = "DTMessaging";
    private static final boolean DEBUG = true; // Set to false in production

    public static void d(String tag, String message) {
        if (DEBUG) {
            Log.d(APP_TAG + "_" + tag, message);
        }
    }

    public static void i(String tag, String message) {
        if (DEBUG) {
            Log.i(APP_TAG + "_" + tag, message);
        }
    }

    public static void w(String tag, String message) {
        Log.w(APP_TAG + "_" + tag, message);
    }

    public static void e(String tag, String message) {
        Log.e(APP_TAG + "_" + tag, message);
    }

    public static void e(String tag, String message, Throwable throwable) {
        Log.e(APP_TAG + "_" + tag, message, throwable);
    }
}