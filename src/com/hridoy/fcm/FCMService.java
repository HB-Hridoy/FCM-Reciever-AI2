package com.hridoy.fcm;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import com.google.appinventor.components.runtime.util.YailList;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class FCMService extends FirebaseMessagingService {

    private static final String TAG = "FCMService";

    // Increments per notification so each gets a unique ID in the tray
    private static final AtomicInteger NOTIF_COUNTER = new AtomicInteger(1000);

    // FCM internal system extras — never exposed to blocks
    private static final List<String> SYSTEM_KEYS = Arrays.asList(
            "google.message_id", "google.sent_time", "google.ttl",
            "google.original_message_id", "collapse_key", "from",
            FCM.KEY_TITLE, FCM.KEY_BODY, FCM.KEY_IMAGE,
            FCM.KEY_MESSAGE_ID
    );

    // ================================================================
    // TOKEN REFRESH
    // ================================================================

    @Override
    public void onNewToken(String token) {
        super.onNewToken(token);
        Log.d(TAG, "New token: " + token);
        FCM.dispatchTokenRefreshed(token);
        // Also persist for when no extension instance is active
        getSharedPreferences("FCMExtPrefs", Context.MODE_PRIVATE)
                .edit().putString("fcm_token", token).apply();
    }

    // ================================================================
    // MESSAGE RECEIVED
    // Called when:
    //   FOREGROUND    — always, for all message types
    //   BACKGROUND    — only for data-only messages
    //   KILLED        — only for data-only messages
    // Notification-type messages in background/killed go directly
    // to the system tray and bypass this method entirely.
    // ================================================================

    @Override
    public void onMessageReceived(RemoteMessage remoteMessage) {
        super.onMessageReceived(remoteMessage);

        String from      = remoteMessage.getFrom()      != null ? remoteMessage.getFrom()      : "";
        String messageId = remoteMessage.getMessageId() != null ? remoteMessage.getMessageId() : "";

        Map<String, String> data = remoteMessage.getData();

        // ── Determine message type ───────────────────────────────────
        // We always send data-only from the server.
        // A notification-type message is identified by presence of
        // fcm_title in the data payload (added by our sender backend).
        boolean isNotification = data.containsKey(FCM.KEY_TITLE);

        // ── Extract clean user data payload ─────────────────────────
        List<String> keys   = new ArrayList<>();
        List<String> values = new ArrayList<>();

        for (Map.Entry<String, String> entry : data.entrySet()) {
            if (!SYSTEM_KEYS.contains(entry.getKey())) {
                keys.add(entry.getKey());
                values.add(entry.getValue() != null ? entry.getValue() : "");
            }
        }

        YailList keyList   = YailList.makeList(keys);
        YailList valueList = YailList.makeList(values);

        if (isNotification) {
            // ── Notification-type message ────────────────────────────
            String title   = data.containsKey(FCM.KEY_TITLE) ? data.get(FCM.KEY_TITLE) : "";
            String body    = data.containsKey(FCM.KEY_BODY)  ? data.get(FCM.KEY_BODY)  : "";
            String image   = data.containsKey(FCM.KEY_IMAGE) ? data.get(FCM.KEY_IMAGE) : "";

            Log.d(TAG, "Notification received: " + title);

            // Always fire the event so blocks can react
            FCM.dispatchNotificationReceived(from, messageId, title, body, keyList, valueList);

            // Show notification:
            // In foreground — respect SetShowForegroundNotifications()
            // In background — always show (this method is only called for data-only
            //                 messages in background, so always build the notification)
            boolean appInForeground = isAppInForeground();

            if (!appInForeground || FCM.shouldShowForegroundNotification()) {
                showNotification(title, body, image, messageId, data);
            }

        } else {
            // ── Data-only message ────────────────────────────────────
            Log.d(TAG, "Data message received");
            FCM.dispatchMessageReceived(from, messageId, keyList, valueList);
        }
    }

    // ================================================================
    // BUILD AND SHOW NOTIFICATION
    // ================================================================

    private void showNotification(
            String title,
            String body,
            String imageUrl,
            String messageId,
            Map<String, String> fullData) {

        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        String channelId   = FCM.getChannelId();
        String channelName = FCM.getChannelName();
        String channelDesc = FCM.getChannelDescription();

        // Create channel (Android 8+) — safe to call repeatedly
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    channelId, channelName, NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription(channelDesc);
            manager.createNotificationChannel(channel);
        }

        // ── Build tap intent ─────────────────────────────────────────
        // Launches the app's main launcher activity.
        // Passes FCM data as extras so AppOpenedFromNotification fires.
        Intent tapIntent = getPackageManager()
                .getLaunchIntentForPackage(getPackageName());

        if (tapIntent == null) {
            // Fallback — build a generic launch intent
            tapIntent = new Intent();
            tapIntent.setPackage(getPackageName());
        }

        tapIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        // Pass all data as extras for AppOpenedFromNotification
        tapIntent.putExtra(FCM.KEY_MESSAGE_ID, messageId);

        for (Map.Entry<String, String> entry : fullData.entrySet()) {
            if (!SYSTEM_KEYS.contains(entry.getKey())) {
                tapIntent.putExtra(entry.getKey(), entry.getValue());
            }
        }

        int requestCode = NOTIF_COUNTER.getAndIncrement();

        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                requestCode,
                tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT
                        | PendingIntent.FLAG_IMMUTABLE
        );

        // ── Build notification ────────────────────────────────────────
        int smallIconRes = getApplicationInfo().icon;

        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(this, channelId)
                        .setSmallIcon(smallIconRes)
                        .setContentTitle(title)
                        .setContentText(body)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setAutoCancel(true)
                        .setContentIntent(pendingIntent)
                        .setStyle(new NotificationCompat.BigTextStyle().bigText(body));

        // Large image if imageUrl provided
        if (imageUrl != null && !imageUrl.isEmpty()) {
            Bitmap imageBitmap = downloadBitmap(imageUrl);
            if (imageBitmap != null) {
                builder.setLargeIcon(imageBitmap)
                        .setStyle(new NotificationCompat.BigPictureStyle()
                                .bigPicture(imageBitmap)
                                .bigLargeIcon((Bitmap) null));  // hide large icon when expanded
            }
        }

        manager.notify(requestCode, builder.build());
        Log.d(TAG, "Notification shown, id: " + NOTIF_COUNTER.get());
    }

    // ================================================================
    // DOWNLOAD BITMAP FOR NOTIFICATION IMAGE
    // ================================================================

    private Bitmap downloadBitmap(String imageUrl) {
        try {
            URL url = new URL(imageUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setDoInput(true);
            conn.connect();
            InputStream stream = conn.getInputStream();
            Bitmap bitmap = BitmapFactory.decodeStream(stream);
            stream.close();
            conn.disconnect();
            return bitmap;
        } catch (Exception e) {
            Log.w(TAG, "Failed to download notification image: " + e.getMessage());
            return null;
        }
    }

    // ================================================================
    // FOREGROUND DETECTION
    // Checks if any activity from this app is currently visible.
    // ================================================================

    private boolean isAppInForeground() {
        android.app.ActivityManager am =
                (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return false;

        List<android.app.ActivityManager.RunningAppProcessInfo> processes =
                am.getRunningAppProcesses();
        if (processes == null) return false;

        String pkg = getPackageName();
        for (android.app.ActivityManager.RunningAppProcessInfo proc : processes) {
            if (proc.processName.equals(pkg)
                    && proc.importance ==
                    android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                return true;
            }
        }
        return false;
    }

    // ================================================================
    // FCM DELIVERY CALLBACKS (optional)
    // ================================================================

    @Override
    public void onDeletedMessages() {
        Log.w(TAG, "Messages deleted from FCM server — device was offline too long");
    }

    @Override
    public void onSendError(String msgId, Exception e) {
        Log.e(TAG, "Send error for " + msgId + ": " + e.getMessage());
    }
}