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
import androidx.core.app.Person;
import androidx.core.graphics.drawable.IconCompat;
import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import com.google.appinventor.components.runtime.util.YailDictionary;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class FCMService extends FirebaseMessagingService {

    private static final String TAG = "FCMService";

    private static final AtomicInteger NOTIF_COUNTER = new AtomicInteger(1000);

    // Single background thread for all FCMService work to prevent ANRs
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ── Messaging style state ────────────────────────────────────────
    // Key = personId (individual) or groupId (group)
    // Stores accumulated MessagingStyle so messages stack correctly
    private static final ConcurrentHashMap<String, NotificationCompat.MessagingStyle>
            activeStyles = new ConcurrentHashMap<>();

    // Fixed notification ID per conversation/group key
    private static final ConcurrentHashMap<String, Integer>
            styleNotifIds = new ConcurrentHashMap<>();

    // Caches processed group avatars globally so incoming senders cannot overwrite group imagery
    private static final ConcurrentHashMap<String, Bitmap>
            groupIconCache = new ConcurrentHashMap<>();

    // ── System keys — stripped from user-facing data dict ────────────
    private static final List<String> SYSTEM_KEYS = Arrays.asList(
            "google.message_id", "google.sent_time", "google.ttl",
            "google.original_message_id", "collapse_key", "from",
            FCM.KEY_TITLE, FCM.KEY_BODY, FCM.KEY_IMAGE,
            FCM.KEY_MESSAGE_ID, FCM.KEY_SMALL_ICON, FCM.KEY_LARGE_ICON,
            FCM.KEY_NOTIFICATION_STYLE
    );

    // ================================================================
    // STATIC CLEAR — called from FCM.dispatchFromIntent on notification tap
    // ================================================================

    static void clearConversation(String key) {
        if (key == null || key.isEmpty()) return;
        activeStyles.remove(key);
        styleNotifIds.remove(key);
        groupIconCache.remove(key);
        Log.d(TAG, "Conversation cleared: " + key);
    }

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
    // MESSAGE RECEIVED — hand off to background thread immediately
    // ================================================================

    @Override
    public void onMessageReceived(RemoteMessage remoteMessage) {
        super.onMessageReceived(remoteMessage);
        final String from      = remoteMessage.getFrom()      != null ? remoteMessage.getFrom()      : "";
        final String messageId = remoteMessage.getMessageId() != null ? remoteMessage.getMessageId() : "";
        final Map<String, String> data = remoteMessage.getData();
        executor.execute(() -> processMessage(from, messageId, data));
    }

    // ================================================================
    // MESSAGE PROCESSING — background thread
    // ================================================================

    private void processMessage(String from, String messageId, Map<String, String> data) {
        boolean isNotification = data.containsKey(FCM.KEY_NOTIFICATION_STYLE);

        // Build clean user data dict — strip all system keys
        YailDictionary dataDict = new YailDictionary();
        for (Map.Entry<String, String> entry : data.entrySet()) {
            if (!SYSTEM_KEYS.contains(entry.getKey())) {
                dataDict.put(entry.getKey(), entry.getValue() != null ? entry.getValue() : "");
            }
        }

        if (isNotification) {

            // Build clean notification style data dict
            String notificationStyleJson = data.get(FCM.KEY_NOTIFICATION_STYLE);
            YailDictionary styleDict = new YailDictionary();

            if (notificationStyleJson != null && !notificationStyleJson.isEmpty()) {
                try {
                    JSONObject json = new JSONObject(notificationStyleJson);

                    Iterator<String> keys = json.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        styleDict.put(key, json.optString(key, ""));
                    }
                } catch (JSONException e) {
                    Log.e(TAG, "Failed to parse notificationStyle: " + notificationStyleJson, e);
                }
            }

            // Dispatch event to blocks (posts to main thread internally)
            FCM.dispatchNotificationReceived(from, messageId, styleDict, dataDict);

            if (!isAppInForeground() || FCM.shouldShowForegroundNotification()) {
                showNotification(messageId, data);
            }
        } else {
            FCM.dispatchMessageReceived(from, messageId, dataDict);
        }
    }

    // ================================================================
    // SHOW NOTIFICATION — routes to correct style builder
    // ================================================================

    private void showNotification(String messageId, Map<String, String> data) {
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;

        String channelId   = FCM.getChannelId();
        String channelName = FCM.getChannelName();
        String channelDesc = FCM.getChannelDescription();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    channelId, channelName, NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription(channelDesc);
            manager.createNotificationChannel(channel);
        }

        // Parse notificationStyle JSON
        String styleJson = data.getOrDefault(FCM.KEY_NOTIFICATION_STYLE, "");
        JSONObject style = null;
        if (!styleJson.isEmpty()) {
            try { style = new JSONObject(styleJson); } catch (Exception e) {
                Log.w(TAG, "Invalid notificationStyle JSON: " + e.getMessage());
            }
        }

        String styleName = style != null ? style.optString("style", "basic") : "basic";

        // Resolve small icon
        String smallIconValue = data.getOrDefault(FCM.KEY_SMALL_ICON, "");
        Bitmap smallIconBitmap = resolveIconBitmap(smallIconValue);

        // Build tap intent
        TapIntents tap = buildTapIntent(messageId, data, NOTIF_COUNTER.get());
        PendingIntent tapIntent = tap.pending;

        // Route to correct style
        switch (styleName) {
            case "bigText":
                showBigText(manager, channelId, messageId, style, smallIconBitmap, tapIntent);
                break;
            case "bigPicture":
                showBigPicture(manager, channelId, messageId, style, smallIconBitmap, tapIntent);
                break;
            case "individualMessage":
                showIndividualMessage(manager, channelId, messageId, style, smallIconBitmap, tap);
                break;
            case "groupMessage":
                showGroupMessage(manager, channelId, messageId, style, smallIconBitmap, tap);
                break;
            default:
                showBasic(manager, channelId, messageId, style, smallIconBitmap, tapIntent);
                break;
        }
    }

    // ================================================================
    // STYLE: basic
    // ================================================================

    private void showBasic(NotificationManager manager, String channelId,
                           String messageId, JSONObject style,
                           Bitmap smallIconBitmap, PendingIntent tapIntent) {

        String title = style != null ? style.optString("title", "") : "";
        String body  = style != null ? style.optString("body",  "") : "";

        int notifId = NOTIF_COUNTER.getAndIncrement();

        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(this, channelId)
                        .setContentTitle(title)
                        .setContentText(body)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setAutoCancel(true)
                        .setContentIntent(tapIntent);

        applySmallIcon(builder, smallIconBitmap);

        manager.notify(notifId, builder.build());
        Log.d(TAG, "basic notif id=" + notifId);
    }

    // ================================================================
    // STYLE: bigText
    // ================================================================

    private void showBigText(NotificationManager manager, String channelId,
                             String messageId, JSONObject style,
                             Bitmap smallIconBitmap, PendingIntent tapIntent) {

        String title = style != null ? style.optString("title", "") : "";
        String body  = style != null ? style.optString("body",  "") : "";

        int notifId = NOTIF_COUNTER.getAndIncrement();

        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(this, channelId)
                        .setContentTitle(title)
                        .setContentText(body)
                        .setStyle(new NotificationCompat.BigTextStyle()
                                .bigText(body)
                                .setBigContentTitle(title))
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setAutoCancel(true)
                        .setContentIntent(tapIntent);

        applySmallIcon(builder, smallIconBitmap);

        manager.notify(notifId, builder.build());
        Log.d(TAG, "bigText notif id=" + notifId);
    }

    // ================================================================
    // STYLE: bigPicture
    // ================================================================

    private void showBigPicture(NotificationManager manager, String channelId,
                                String messageId, JSONObject style,
                                Bitmap smallIconBitmap, PendingIntent tapIntent) {

        String title      = style != null ? style.optString("title",      "") : "";
        String body       = style != null ? style.optString("body",       "") : "";
        String largeIconUrl = style != null ? style.optString("largeIcon", "") : "";
        String bigPicUrl  = style != null ? style.optString("bigPicture", "") : "";

        Bitmap largeIcon  = !largeIconUrl.isEmpty() ? downloadBitmap(largeIconUrl) : null;
        Bitmap bigPic     = !bigPicUrl.isEmpty()    ? downloadBitmap(bigPicUrl)    : null;

        int notifId = NOTIF_COUNTER.getAndIncrement();

        NotificationCompat.BigPictureStyle picStyle =
                new NotificationCompat.BigPictureStyle()
                        .setBigContentTitle(title)
                        .setSummaryText(body);

        if (bigPic != null) picStyle.bigPicture(bigPic);

        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(this, channelId)
                        .setContentTitle(title)
                        .setContentText(body)
                        .setStyle(picStyle)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setAutoCancel(true)
                        .setContentIntent(tapIntent);

        applySmallIcon(builder, smallIconBitmap);
        if (largeIcon != null) builder.setLargeIcon(largeIcon);

        manager.notify(notifId, builder.build());
        Log.d(TAG, "bigPicture notif id=" + notifId);
    }

    // ================================================================
    // STYLE: individualMessage
    // ================================================================

    private void showIndividualMessage(NotificationManager manager, String channelId,
                                       String messageId, JSONObject style,
                                       Bitmap smallIconBitmap, TapIntents tap) {

        if (style == null) return;

        String personId   = style.optString("personId",   "");
        String personName = style.optString("personName", "Unknown");
        String personIconUrl = style.optString("personIcon", "");
        String personImageUrl = style.optString("personImage", "");
        String message    = style.optString("message",    "");

        if (personId.isEmpty()) return;

        Bitmap rawIcon = !personIconUrl.isEmpty() ? downloadBitmap(personIconUrl) : null;
        if (rawIcon == null && !personImageUrl.isEmpty()) {
            rawIcon = downloadBitmap(personImageUrl);
        }
        Bitmap circularAvatar = rawIcon != null ? getCircularBitmap(rawIcon) : null;

        Person.Builder senderBuilder = new Person.Builder().setName(personName).setKey(personId);
        if (circularAvatar != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            senderBuilder.setIcon(IconCompat.createWithBitmap(circularAvatar));
        }
        Person sender = senderBuilder.build();

        // FIXED: Set explicit space user placeholder to satisfy Android validation checks cleanly
        Person userMe = new Person.Builder().setName(" ").setKey("user_me").build();

        styleNotifIds.putIfAbsent(personId, NOTIF_COUNTER.getAndIncrement());
        int notifId = styleNotifIds.get(personId);

        NotificationCompat.MessagingStyle msgStyle;
        if (activeStyles.containsKey(personId)) {
            msgStyle = activeStyles.get(personId);
        } else {
            msgStyle = new NotificationCompat.MessagingStyle(userMe);
            msgStyle.setGroupConversation(false);
            activeStyles.put(personId, msgStyle);
        }

        msgStyle.addMessage(message, System.currentTimeMillis(), sender);

        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(this, channelId)
                        .setStyle(msgStyle)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setAutoCancel(true)
                        .setContentIntent(tap.pending)
                        .setShortcutId(personId);

        applySmallIcon(builder, smallIconBitmap);
        if (circularAvatar != null) {
            builder.setLargeIcon(circularAvatar);
        }

        publishShortcut(personId, personName, sender, tap.raw);
        manager.notify(notifId, builder.build());
    }

    // ================================================================
    // STYLE: groupMessage
    // ================================================================

    private void showGroupMessage(NotificationManager manager, String channelId,
                                  String messageId, JSONObject style,
                                  Bitmap smallIconBitmap, TapIntents tap) {
        if (style == null) return;

        String groupId    = style.optString("groupId",    "");
        String groupName  = style.optString("groupName",  "Group");
        String groupIconUrl = style.optString("groupIcon", "");
        String personId   = style.optString("personId",   "");
        String personName = style.optString("personName", "Unknown");
        String personIconUrl = style.optString("personIcon", "");
        String message    = style.optString("message",    "");

        if (groupId.isEmpty()) return;

        styleNotifIds.putIfAbsent(groupId, NOTIF_COUNTER.getAndIncrement());
        int notifId = styleNotifIds.get(groupId);

        // 1. Process Sender Details (The person typing right now)
        Bitmap rawPersonIcon = !personIconUrl.isEmpty() ? downloadBitmap(personIconUrl) : null;
        Bitmap circularPersonIcon = rawPersonIcon != null ? getCircularBitmap(rawPersonIcon) : null;

        Person.Builder senderBuilder = new Person.Builder().setName(personName).setKey(personId);
        if (circularPersonIcon != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            senderBuilder.setIcon(IconCompat.createWithBitmap(circularPersonIcon));
        }
        Person sender = senderBuilder.build();

        NotificationCompat.MessagingStyle msgStyle;
        Bitmap groupLargeIcon = null;

        // 2. Resolve Group Imagery Context
        if (activeStyles.containsKey(groupId)) {
            msgStyle = activeStyles.get(groupId);

            // Always try to download/refresh the group icon if provided, otherwise check cache
            if (!groupIconUrl.isEmpty()) {
                Bitmap rawGroup = downloadBitmap(groupIconUrl);
                if (rawGroup != null) {
                    groupLargeIcon = getCircularBitmap(rawGroup);
                    groupIconCache.put(groupId, groupLargeIcon);
                }
            }
            if (groupLargeIcon == null && groupIconCache.containsKey(groupId)) {
                groupLargeIcon = groupIconCache.get(groupId);
            }
        } else {
            // First time initialization loop
            Bitmap rawGroupIcon = !groupIconUrl.isEmpty() ? downloadBitmap(groupIconUrl) : null;
            if (rawGroupIcon != null) {
                groupLargeIcon = getCircularBitmap(rawGroupIcon);
                groupIconCache.put(groupId, groupLargeIcon);
            } else if (circularPersonIcon != null) {
                groupLargeIcon = circularPersonIcon;
                groupIconCache.put(groupId, groupLargeIcon);
            }

            Person userMe = new Person.Builder().setName("Me").setKey("user_me").build();
            msgStyle = new NotificationCompat.MessagingStyle(userMe);
            msgStyle.setConversationTitle(groupName);
            msgStyle.setGroupConversation(true);
            activeStyles.put(groupId, msgStyle);
        }

        msgStyle.addMessage(message, System.currentTimeMillis(), sender);

        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(this, channelId)
                        .setStyle(msgStyle)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setAutoCancel(true)
                        .setContentIntent(tap.pending)
                        .setShortcutId(groupId);

        applySmallIcon(builder, smallIconBitmap);

        if (groupLargeIcon != null) {
            builder.setLargeIcon(groupLargeIcon);
        } else if (circularPersonIcon != null) {
            builder.setLargeIcon(circularPersonIcon);
        }

        // 3. Build a dedicated Person profile for the GROUP shortcut, not the sender
        Person.Builder groupPersonBuilder = new Person.Builder().setName(groupName).setKey(groupId);
        if (groupLargeIcon != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            groupPersonBuilder.setIcon(IconCompat.createWithBitmap(groupLargeIcon));
        }
        Person groupShortcutPerson = groupPersonBuilder.build();

        // Publish the shortcut bound to the Group's photo identity
        publishShortcut(groupId, groupName, groupShortcutPerson, tap.raw);

        manager.notify(notifId, builder.build());
    }

    // ================================================================
    // HELPERS
    // ================================================================

    private TapIntents buildTapIntent(String messageId, Map<String, String> data, int requestCode) {
        Intent intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (intent == null) {
            intent = new Intent();
            intent.setPackage(getPackageName());
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra(FCM.KEY_MESSAGE_ID, messageId);

        for (Map.Entry<String, String> entry : data.entrySet()) {
            if (!SYSTEM_KEYS.contains(entry.getKey())) {
                intent.putExtra(entry.getKey(), entry.getValue());
            }
        }

        String nsJson = data.getOrDefault(FCM.KEY_NOTIFICATION_STYLE, "");
        if (!nsJson.isEmpty()) {
            try {
                JSONObject ns = new JSONObject(nsJson);
                String pid = ns.optString("personId", "");
                String gid = ns.optString("groupId",  "");
                if (!pid.isEmpty()) intent.putExtra("fcm_person_id", pid);
                if (!gid.isEmpty()) intent.putExtra("fcm_group_id",  gid);
            } catch (Exception ignored) {}
        }

        int pendingFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;

        PendingIntent pending = PendingIntent.getActivity(this, requestCode, intent, pendingFlags);
        return new TapIntents(intent, pending);
    }

    private void applySmallIcon(NotificationCompat.Builder builder, Bitmap smallIconBitmap) {
        if (smallIconBitmap != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            builder.setSmallIcon(IconCompat.createWithBitmap(smallIconBitmap));
        } else {
            builder.setSmallIcon(getApplicationInfo().icon);
        }
    }

    private Bitmap resolveIconBitmap(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null;
        if (value.startsWith("http://") || value.startsWith("https://")) {
            return downloadBitmap(value);
        }
        try {
            String name = value.contains(".") ? value : value + ".png";
            InputStream is = getAssets().open(name);
            Bitmap bmp = BitmapFactory.decodeStream(is);
            is.close();
            return bmp;
        } catch (Exception e) {
            Log.w(TAG, "Icon asset not found: " + value);
            return null;
        }
    }

    private Bitmap getCircularBitmap(Bitmap bitmap) {
        if (bitmap == null) return null;

        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int diameter = Math.min(width, height);

        Bitmap output = Bitmap.createBitmap(diameter, diameter, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(output);

        final int color = 0xff424242;
        final android.graphics.Paint paint = new android.graphics.Paint();
        final android.graphics.Rect rect = new android.graphics.Rect(0, 0, diameter, diameter);

        int left = (width - diameter) / 2;
        int top = (height - diameter) / 2;
        android.graphics.Rect srcRect = new android.graphics.Rect(left, top, left + diameter, top + diameter);

        paint.setAntiAlias(true);
        canvas.drawARGB(0, 0, 0, 0);
        paint.setColor(color);

        canvas.drawCircle(diameter / 2f, diameter / 2f, diameter / 2f, paint);
        paint.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN));
        canvas.drawBitmap(bitmap, srcRect, rect, paint);

        return output;
    }

    // FIXED: Fully removed the 10-character string substring boundary constraint
    private void publishShortcut(String shortcutId, String personName, Person person, Intent tapIntent) {
        ShortcutInfoCompat.Builder builder = new ShortcutInfoCompat.Builder(this, shortcutId)
                .setLongLived(true)
                .setIntent(tapIntent)
                .setShortLabel(personName)
                .setPerson(person);

        // Set shortcut icon from Person's icon if available
        if (person.getIcon() != null) {
            builder.setIcon(person.getIcon());
        }

        ShortcutManagerCompat.pushDynamicShortcut(this, builder.build());
    }

    /** Holds both raw Intent and wrapped PendingIntent from a single build call. */
    private static class TapIntents {
        final Intent        raw;
        final PendingIntent pending;

        TapIntents(Intent raw, PendingIntent pending) {
            this.raw     = raw;
            this.pending = pending;
        }
    }

    // ================================================================
    // BITMAP DOWNLOAD — always called from background thread
    // ================================================================

    private Bitmap downloadBitmap(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setDoInput(true);
            conn.connect();
            InputStream stream = conn.getInputStream();
            Bitmap bmp = BitmapFactory.decodeStream(stream);
            stream.close();
            return bmp;
        } catch (Exception e) {
            Log.w(TAG, "Download failed: " + url + " — " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private boolean isAppInForeground() {
        android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return false;
        List<android.app.ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
        if (procs == null) return false;
        String pkg = getPackageName();
        for (android.app.ActivityManager.RunningAppProcessInfo p : procs) {
            if (p.processName.equals(pkg) && p.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND)
                return true;
        }
        return false;
    }

    // ================================================================
    // FCM CALLBACKS
    // ================================================================

    @Override public void onDeletedMessages() {
        Log.w(TAG, "Messages deleted — device offline too long");
    }

    @Override public void onSendError(String msgId, Exception e) {
        Log.e(TAG, "Send error " + msgId + ": " + e.getMessage());
    }
}