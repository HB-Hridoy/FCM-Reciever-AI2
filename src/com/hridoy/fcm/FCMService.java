package com.hridoy.fcm;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import com.google.appinventor.components.runtime.util.YailList;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class FCMService extends FirebaseMessagingService {

    private static final String TAG = "MyFCMService";
    private static final String CHANNEL_ID = "fcm_default_channel";
    private static final String CHANNEL_NAME = "Push Notifications";
    private static final int NOTIF_ID = 1001;

    // ----------------------------------------------------------------
    // Token refresh callback
    // Called when:
    // 1. App installs for the first time
    // 2. User clears app data
    // 3. User restores app on new device
    // 4. Firebase rotates tokens (security)
    // ----------------------------------------------------------------
    @Override
    public void onNewToken(String token) {
        super.onNewToken(token);
        Log.d(TAG, "New FCM token: " + token);

        // Attempt to dispatch to live extension instance
        FCM.dispatchTokenRefreshed(token);

        // Also persist locally for when app is not running.
        // The extension reads this on next Initialize().
        getSharedPreferences("FCMExtPrefs", Context.MODE_PRIVATE)
                .edit()
                .putString("fcm_token", token)
                .apply();
    }

    // ----------------------------------------------------------------
    // Message received
    // Called when:
    // - App is in FOREGROUND: always called
    // - App is in BACKGROUND: called ONLY for data-only messages
    //   (notification messages go directly to system tray)
    // - App is KILLED: called when user taps notification
    //   (FCM delivers a data message on next app start)
    // ----------------------------------------------------------------
    @Override
    public void onMessageReceived(RemoteMessage remoteMessage) {
        super.onMessageReceived(remoteMessage);

        Log.d(TAG, "Message received from: " + remoteMessage.getFrom());
        Log.d(TAG, "Message ID: " + remoteMessage.getMessageId());

        String from = remoteMessage.getFrom() != null ? remoteMessage.getFrom() : "";
        String messageId = remoteMessage.getMessageId() != null ? remoteMessage.getMessageId() : "";

        // Extract data payload
        Map<String, String> data = remoteMessage.getData();
        List<String> keys = new ArrayList<>(data.keySet());
        List<String> values = new ArrayList<>();
        for (String key : keys) {
            values.add(data.get(key));
        }

        YailList keyList = YailList.makeList(keys);
        YailList valueList = YailList.makeList(values);

        // Dispatch to extension if app is in foreground
        FCM.dispatchMessageReceived(from, messageId, keyList, valueList);

        // If notification payload exists, post a notification
        // (This handles foreground notification display, which FCM does NOT
        // do automatically — it only auto-displays in background)
        if (remoteMessage.getNotification() != null) {
            RemoteMessage.Notification notif = remoteMessage.getNotification();
            String title = notif.getTitle() != null ? notif.getTitle() : "";
            String body = notif.getBody() != null ? notif.getBody() : "";
            showNotification(title, body, messageId, data);
        }
    }

    // ----------------------------------------------------------------
    // Show a notification (foreground display)
    // ----------------------------------------------------------------
    private void showNotification(String title, String body,
                                  String messageId, Map<String, String> data) {

        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        // Android 8+ requires a channel
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("FCM push notifications");
            manager.createNotificationChannel(channel);
        }

        // Build intent to open app when notification is tapped
        Intent intent = getPackageManager()
                .getLaunchIntentForPackage(getPackageName());
        if (intent != null) {
            // Pass data payload so NotificationClicked event can fire
            for (Map.Entry<String, String> entry : data.entrySet()) {
                intent.putExtra(entry.getKey(), entry.getValue());
            }
            intent.putExtra("fcm_message_id", messageId);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        }

        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                        : PendingIntent.FLAG_UPDATE_CURRENT
        );

        // Use app's own launcher icon for notification
        int iconRes = getApplicationInfo().icon;

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(iconRes)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent);

        manager.notify(NOTIF_ID, builder.build());
    }

    // ----------------------------------------------------------------
    // Message send error (when using FCM upstream messaging)
    // ----------------------------------------------------------------
    @Override
    public void onSendError(String msgId, Exception exception) {
        Log.e(TAG, "Error sending message: " + msgId, exception);
    }

    // ----------------------------------------------------------------
    // Message deleted from server (device was offline too long)
    // FCM only retains messages for 4 weeks
    // ----------------------------------------------------------------
    @Override
    public void onDeletedMessages() {
        Log.w(TAG, "Messages deleted from FCM server. Some messages were not delivered.");
    }
}