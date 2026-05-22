package com.hridoy.fcm;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import com.google.appinventor.components.runtime.util.YailDictionary;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class FCMService extends FirebaseMessagingService {

    private static final String TAG = "FCMService";

    private static final AtomicInteger NOTIF_COUNTER = new AtomicInteger(1000);

    // Single background thread for all FCMService work.
    // onMessageReceived() runs on main thread — we immediately hand off
    // all work to this executor to prevent ANR.
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

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
        // Token refresh is lightweight — just a prefs write + event dispatch
        FCM.dispatchTokenRefreshed(token);
        getSharedPreferences("FCMExtPrefs", Context.MODE_PRIVATE)
                .edit().putString("fcm_token", token).apply();
    }

    // ================================================================
    // MESSAGE RECEIVED
    // ================================================================

    @Override
    public void onMessageReceived(RemoteMessage remoteMessage) {
        super.onMessageReceived(remoteMessage);

        // Capture all data immediately on main thread — RemoteMessage is not
        // guaranteed to be valid after this method returns
        final String from      = remoteMessage.getFrom()      != null ? remoteMessage.getFrom()      : "";
        final String messageId = remoteMessage.getMessageId() != null ? remoteMessage.getMessageId() : "";
        final Map<String, String> data = remoteMessage.getData();

        // Hand off ALL processing to background thread immediately.
        // This prevents ANR when downloading images or doing any I/O.
        executor.execute(() -> processMessage(from, messageId, data));
    }

    // ================================================================
    // MESSAGE PROCESSING — runs on background thread
    // ================================================================

    private void processMessage(String from, String messageId, Map<String, String> data) {
        boolean isNotification = data.containsKey(FCM.KEY_TITLE);

        // Build clean data dictionary — skip FCM system keys
        YailDictionary dataDict = new YailDictionary();
        for (Map.Entry<String, String> entry : data.entrySet()) {
            if (!SYSTEM_KEYS.contains(entry.getKey())) {
                dataDict.put(entry.getKey(),
                        entry.getValue() != null ? entry.getValue() : "");
            }
        }

        if (isNotification) {
            String title  = data.getOrDefault(FCM.KEY_TITLE, "");
            String body   = data.getOrDefault(FCM.KEY_BODY, "");
            String image  = data.getOrDefault(FCM.KEY_IMAGE, "");

            Log.d(TAG, "Notification: " + title);

            // Dispatch event to blocks (posts to main thread internally)
            FCM.dispatchNotificationReceived(from, messageId, title, body, dataDict);

            // Decide whether to show notification
            boolean inForeground = isAppInForeground();
            if (!inForeground || FCM.shouldShowForegroundNotification()) {
                // Download image here on background thread — safe, no ANR risk
                Bitmap imageBitmap = null;
                if (image != null && !image.isEmpty()) {
                    imageBitmap = downloadBitmap(image);
                }
                // Post notification build to main thread
                // (NotificationManager.notify can be called from any thread
                // but NotificationCompat.Builder accesses Context safely here)
                final Bitmap finalBitmap = imageBitmap;
                showNotification(title, body, finalBitmap, messageId, data);
            }

        } else {
            Log.d(TAG, "Data message");
            FCM.dispatchMessageReceived(from, messageId, dataDict);
        }
    }

    // ================================================================
    // BUILD AND SHOW NOTIFICATION — called from background thread
    // ================================================================

    private void showNotification(
            String title,
            String body,
            Bitmap imageBitmap,
            String messageId,
            Map<String, String> fullData) {

        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        String channelId   = FCM.getChannelId();
        String channelName = FCM.getChannelName();
        String channelDesc = FCM.getChannelDescription();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    channelId, channelName, NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription(channelDesc);
            manager.createNotificationChannel(channel);
        }

        // Build tap Intent — opens app's launcher Activity with FCM data
        Intent tapIntent = getPackageManager()
                .getLaunchIntentForPackage(getPackageName());

        if (tapIntent == null) {
            tapIntent = new Intent();
            tapIntent.setPackage(getPackageName());
        }

        tapIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        // Attach FCM data as extras
        tapIntent.putExtra(FCM.KEY_MESSAGE_ID, messageId);
        for (Map.Entry<String, String> entry : fullData.entrySet()) {
            if (!SYSTEM_KEYS.contains(entry.getKey())) {
                tapIntent.putExtra(entry.getKey(), entry.getValue());
            }
        }

        int requestCode = NOTIF_COUNTER.getAndIncrement();
        int piFlags     = PendingIntent.FLAG_UPDATE_CURRENT
                | PendingIntent.FLAG_IMMUTABLE;

        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, requestCode, tapIntent, piFlags);

        int smallIcon = getApplicationInfo().icon;

        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(this, channelId)
                        .setSmallIcon(smallIcon)
                        .setContentTitle(title)
                        .setContentText(body)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setAutoCancel(true)
                        .setContentIntent(pendingIntent)
                        .setStyle(new NotificationCompat.BigTextStyle().bigText(body));

        if (imageBitmap != null) {
            builder.setLargeIcon(imageBitmap)
                    .setStyle(new NotificationCompat.BigPictureStyle()
                            .bigPicture(imageBitmap)
                            .bigLargeIcon((Bitmap) null));
        }

        manager.notify(requestCode, builder.build());
        Log.d(TAG, "Notification shown id=" + requestCode);
    }

    // ================================================================
    // BITMAP DOWNLOAD — always called from background thread
    // ================================================================

    private Bitmap downloadBitmap(String imageUrl) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(imageUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setDoInput(true);
            conn.connect();
            InputStream stream = conn.getInputStream();
            Bitmap bitmap = BitmapFactory.decodeStream(stream);
            stream.close();
            return bitmap;
        } catch (Exception e) {
            Log.w(TAG, "Image download failed: " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ================================================================
    // FOREGROUND DETECTION — called from background thread
    // ================================================================

    private boolean isAppInForeground() {
        android.app.ActivityManager am =
                (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return false;

        List<android.app.ActivityManager.RunningAppProcessInfo> procs =
                am.getRunningAppProcesses();
        if (procs == null) return false;

        String pkg = getPackageName();
        for (android.app.ActivityManager.RunningAppProcessInfo p : procs) {
            if (p.processName.equals(pkg)
                    && p.importance ==
                    android.app.ActivityManager.RunningAppProcessInfo
                            .IMPORTANCE_FOREGROUND) {
                return true;
            }
        }
        return false;
    }

    // ================================================================
    // FCM CALLBACKS
    // ================================================================

    @Override
    public void onDeletedMessages() {
        Log.w(TAG, "Messages deleted — device was offline too long");
    }

    @Override
    public void onSendError(String msgId, Exception e) {
        Log.e(TAG, "Send error " + msgId + ": " + e.getMessage());
    }
}