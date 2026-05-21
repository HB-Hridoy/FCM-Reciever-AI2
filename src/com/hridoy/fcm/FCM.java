package com.hridoy.fcm;

import com.google.appinventor.components.annotations.DesignerComponent;
import com.google.appinventor.components.annotations.SimpleEvent;
import com.google.appinventor.components.annotations.SimpleFunction;
import com.google.appinventor.components.annotations.SimpleObject;
import com.google.appinventor.components.annotations.SimpleProperty;
import com.google.appinventor.components.annotations.DesignerProperty;
import com.google.appinventor.components.runtime.AndroidNonvisibleComponent;
import com.google.appinventor.components.runtime.Component;
import com.google.appinventor.components.runtime.ComponentContainer;
import com.google.appinventor.components.runtime.EventDispatcher;
import com.google.appinventor.components.runtime.OnDestroyListener;
import com.google.appinventor.components.runtime.OnResumeListener;
import com.google.appinventor.components.runtime.util.YailList;
import com.google.appinventor.components.runtime.util.YailProcedure;

import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@DesignerComponent(
		version = 18,
		versionName = "2.0.0",
		description = "Firebase Cloud Messaging receiver extension. Developed by Hridoy.",
		iconName = "icon.png"
)
public class FCM extends AndroidNonvisibleComponent
		implements Component, OnDestroyListener, OnResumeListener {

	private static final String TAG  = "FCM";

	// SharedPreferences keys
	private static final String PREFS_NAME          = "FCMExtPrefs";
	private static final String PREF_TOKEN          = "fcm_token";
	private static final String PREF_TOPICS         = "fcm_subscribed_topics";
	private static final String PREF_SHOW_FG_NOTIF  = "fcm_show_fg_notifications";

	// FCM reserved data keys — set by sender, stripped before exposing to blocks
	static final String KEY_TITLE      = "fcm_title";
	static final String KEY_BODY       = "fcm_body";
	static final String KEY_IMAGE      = "fcm_image";
	static final String KEY_MESSAGE_ID = "fcm_message_id";

	// Internal FCM extras added by the system — filtered from data payloads
	private static final List<String> SYSTEM_KEYS = Arrays.asList(
			"google.message_id", "google.sent_time", "google.ttl",
			"google.original_message_id", "collapse_key", "from",
			"fcm_message_id", "gcm.notification.body", "gcm.notification.title",
			"gcm.notification.image", "android.support.content.wakelockid",
			"androidx.content.wakelockid",
			KEY_TITLE, KEY_BODY, KEY_IMAGE
	);

	// Notification channel config
	private String channelId          = "fcm_default_channel";
	private String channelName        = "Push Notifications";
	private String channelDescription = "App push notifications";

	// Static bridge — allows MyFCMService to reach the live extension instance
	static WeakReference<FCM> activeInstance;

	private final Activity          activity;
	private final Handler           mainHandler;
	private final SharedPreferences prefs;
	private boolean initialized           = false;
	private boolean showForegroundNotif   = true;

	// ================================================================
	// CONSTRUCTOR
	// ================================================================

	public FCM(ComponentContainer container) {
		super(container.$form());
		this.activity    = container.$context() instanceof Activity
				? (Activity) container.$context()
				: container.$form();
		this.mainHandler = new Handler(Looper.getMainLooper());
		this.prefs       = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
		this.showForegroundNotif = prefs.getBoolean(PREF_SHOW_FG_NOTIF, true);

		container.$form().registerForOnDestroy(this);
		container.$form().registerForOnResume(this);
		activeInstance = new WeakReference<>(this);

		Log.d(TAG, "FCM constructed");
	}

	// ================================================================
	// INITIALIZE
	// ================================================================

	@SimpleFunction(description =
			"Initializes Firebase with your project credentials.\n" +
					"Call this once at app startup before any other FCM method.\n" +
					".\n===============================================================\n.\n" +
					"Parameters from Firebase Console → Project Settings:\n" +
					"  • apiKey        — Web API key\n" +
					"  • applicationId — App ID (e.g. 1:123:android:abc)\n" +
					"  • projectId     — Project ID (e.g. my-app-123)\n" +
					"  • senderId      — Sender ID / Cloud Messaging number\n" +
					"  • storageBucket — Storage bucket (e.g. my-app.appspot.com)\n" +
					".\n===============================================================\n.\n" +
					"Callback parameters:\n" +
					"  1) status       (boolean: true = success, false = failure)\n" +
					"  2) errorMessage (text: empty if success)")
	public void Initialize(
			final String apiKey,
			final String applicationId,
			final String projectId,
			final String senderId,
			final YailProcedure callback) {

		if (!validateCallback("Initialize", callback, 2)) return;

		if (initialized) {
			fireCallback(callback, Boolean.TRUE, "");
			return;
		}

		try {
			FirebaseOptions options = new FirebaseOptions.Builder()
					.setApiKey(apiKey)
					.setApplicationId(applicationId)
					.setProjectId(projectId)
					.setGcmSenderId(senderId)
					.build();

			if (FirebaseApp.getApps(activity).isEmpty()) {
				FirebaseApp.initializeApp(activity, options);
				Log.d(TAG, "FirebaseApp initialized");
			} else {
				Log.d(TAG, "FirebaseApp reused");
			}

			initialized = true;
			fireCallback(callback, Boolean.TRUE, "");

		} catch (Exception e) {
			Log.e(TAG, "Initialize failed", e);
			fireCallback(callback, Boolean.FALSE, e.getMessage());
		}
	}

	// ================================================================
	// NOTIFICATION CHANNEL CONFIGURATION
	// ================================================================

	@SimpleFunction(description =
			"Configures the Android notification channel used for all FCM notifications.\n" +
					"Must be called before the first notification is displayed.\n" +
					"On Android 7 and below this is a no-op.\n" +
					".\n===============================================================\n.\n" +
					"Parameters:\n" +
					"  • channelId          — unique identifier (e.g. 'my_channel')\n" +
					"  • channelName        — visible name shown in system settings\n" +
					"  • channelDescription — description shown in system settings")
	public void SetNotificationChannel(
			String channelId,
			String channelName,
			String channelDescription) {
		this.channelId          = channelId;
		this.channelName        = channelName;
		this.channelDescription = channelDescription;

		// Create channel immediately if Android 8+
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			NotificationManager manager =
					(NotificationManager) activity.getSystemService(Context.NOTIFICATION_SERVICE);
			NotificationChannel channel = new NotificationChannel(
					channelId, channelName, NotificationManager.IMPORTANCE_HIGH);
			channel.setDescription(channelDescription);
			manager.createNotificationChannel(channel);
		}
	}

	// ================================================================
	// FOREGROUND NOTIFICATION TOGGLE
	// ================================================================

	@DesignerProperty(
			editorType = "boolean",
			defaultValue = "True"
	)
	@SimpleProperty(
			description =
					"Controls whether notifications are shown when the app is in foreground.\n" +
					"Resets to true each session — call in Screen.Initialize to change.\n" +
					".\n===============================================================\n.\n" +
					" • true = show notification (default)\n" +
					" • false = suppress, only fire NotificationReceived event"
	)
	public void ShowForegroundNotifications(boolean show) {
		this.showForegroundNotif = show;
	}

	@SimpleProperty(description ="Returns whether foreground notifications are currently enabled.")
	public boolean ShowForegroundNotifications() {
		return showForegroundNotif;
	}

	// ================================================================
	// GET TOKEN
	// ================================================================

	@SimpleFunction(description =
			"Retrieves the current FCM registration token from Firebase.\n" +
					"Tokens can change — always fetch fresh before registering with your server.\n" +
					".\n===============================================================\n.\n" +
					"Callback parameters:\n" +
					"  1) token        (text: the FCM token, empty if failed)\n" +
					"  2) errorMessage (text: empty if success)")
	public void GetToken(final YailProcedure callback) {
		if (!validateCallback("GetToken", callback, 2)) return;
		if (!checkInitialized("GetToken", callback, 2)) return;

		FirebaseMessaging.getInstance().getToken()
				.addOnCompleteListener(task -> {
					if (!task.isSuccessful()) {
						String err = task.getException() != null
								? task.getException().getMessage()
								: "Unknown error";
						Log.e(TAG, "GetToken failed: " + err);
						fireCallback(callback, "", err);
						return;
					}
					String token = task.getResult();
					prefs.edit().putString(PREF_TOKEN, token).apply();
					Log.d(TAG, "Token: " + token);
					fireCallback(callback, token, "");
				});
	}

	// ================================================================
	// GET CACHED TOKEN
	// ================================================================

	@SimpleFunction(description =
			"Returns the last known FCM token from local cache.\n" +
					"Returns empty string if no token has been fetched yet.\n" +
					"Use GetToken() to retrieve a fresh token from Firebase.")
	public String GetCachedToken() {
		return prefs.getString(PREF_TOKEN, "");
	}

	// ================================================================
	// SUBSCRIBE TO TOPIC
	// ================================================================

	@SimpleFunction(description =
			"Subscribes this device to an FCM topic.\n" +
					"Checks local cache first — skips Firebase call if already subscribed.\n" +
					"Topic names must match [a-zA-Z0-9-_.~%] and be under 900 chars.\n" +
					".\n===============================================================\n.\n" +
					"Callback parameters:\n" +
					"  1) status       (boolean: true = success, false = failure)\n" +
					"  2) topic        (text: the topic name)\n" +
					"  3) errorMessage (text: empty if success, 'Already subscribed' if duplicate)")
	public void SubscribeToTopic(final String topic, final YailProcedure callback) {
		if (!validateCallback("SubscribeToTopic", callback, 3)) return;
		if (!checkInitialized("SubscribeToTopic", callback, 3)) return;
		if (!validateTopicName(topic, callback)) return;

		// Check local cache — avoid redundant Firebase call
		if (isTopicSubscribed(topic)) {
			Log.d(TAG, "Already subscribed: " + topic);
			fireCallback(callback, Boolean.FALSE, topic, "Already subscribed to: " + topic);
			return;
		}

		FirebaseMessaging.getInstance().subscribeToTopic(topic)
				.addOnCompleteListener(task -> mainHandler.post(() -> {
					if (task.isSuccessful()) {
						addTopicToCache(topic);
						Log.d(TAG, "Subscribed: " + topic);
						fireCallback(callback, Boolean.TRUE, topic, "");
					} else {
						String err = task.getException() != null
								? task.getException().getMessage() : "Unknown error";
						Log.e(TAG, "Subscribe failed: " + err);
						fireCallback(callback, Boolean.FALSE, topic, err);
					}
				}));
	}

	// ================================================================
	// UNSUBSCRIBE FROM TOPIC
	// ================================================================

	@SimpleFunction(description =
			"Unsubscribes this device from an FCM topic.\n" +
					"The device will no longer receive messages sent to this topic.\n" +
					".\n===============================================================\n.\n" +
					"Callback parameters:\n" +
					"  1) status       (boolean: true = success, false = failure)\n" +
					"  2) topic        (text: the topic name)\n" +
					"  3) errorMessage (text: empty if success)")
	public void UnsubscribeFromTopic(final String topic, final YailProcedure callback) {
		if (!validateCallback("UnsubscribeFromTopic", callback, 3)) return;
		if (!checkInitialized("UnsubscribeFromTopic", callback, 3)) return;

		FirebaseMessaging.getInstance().unsubscribeFromTopic(topic)
				.addOnCompleteListener(task -> mainHandler.post(() -> {
					if (task.isSuccessful()) {
						removeTopicFromCache(topic);
						Log.d(TAG, "Unsubscribed: " + topic);
						fireCallback(callback, Boolean.TRUE, topic, "");
					} else {
						String err = task.getException() != null
								? task.getException().getMessage() : "Unknown error";
						Log.e(TAG, "Unsubscribe failed: " + err);
						fireCallback(callback, Boolean.FALSE, topic, err);
					}
				}));
	}

	// ================================================================
	// SUBSCRIBED TOPICS
	// ================================================================

	@SimpleFunction(description =
			"Returns the list of topics this device is currently subscribed to.\n" +
					"Reads from local cache — does not make a network request.\n" +
					"Returns an empty list if not subscribed to any topics.")
	public YailList SubscribedTopics() {
		Set<String> topics = prefs.getStringSet(PREF_TOPICS, new HashSet<>());
		return YailList.makeList(new ArrayList<>(topics));
	}

	// ================================================================
	// DELETE TOKEN
	// ================================================================

	@SimpleFunction(description =
			"Deletes the current FCM registration token.\n" +
					"Use this on user logout to stop receiving targeted notifications.\n" +
					"A new token is generated on the next GetToken() call.\n" +
					".\n===============================================================\n.\n" +
					"Callback parameters:\n" +
					"  1) status       (boolean: true = success, false = failure)\n" +
					"  2) errorMessage (text: empty if success)")
	public void DeleteToken(final YailProcedure callback) {
		if (!validateCallback("DeleteToken", callback, 2)) return;
		if (!checkInitialized("DeleteToken", callback, 2)) return;

		FirebaseMessaging.getInstance().deleteToken()
				.addOnCompleteListener(task -> mainHandler.post(() -> {
					if (task.isSuccessful()) {
						prefs.edit().remove(PREF_TOKEN).apply();
						Log.d(TAG, "Token deleted");
						fireCallback(callback, Boolean.TRUE, "");
					} else {
						String err = task.getException() != null
								? task.getException().getMessage() : "Unknown error";
						Log.e(TAG, "DeleteToken failed: " + err);
						fireCallback(callback, Boolean.FALSE, err);
					}
				}));
	}

	// ================================================================
	// NOTIFICATION PERMISSION
	// ================================================================

	@SimpleFunction(description =
			"Requests POST_NOTIFICATIONS runtime permission on Android 13+.\n" +
					"No-op on Android 12 and below.\n" +
					"Call from a button click — not from Screen.Initialize.\n" +
					".\n===============================================================\n.\n" +
					"Callback parameters:\n" +
					"  1) granted      (boolean: true = already granted, false = dialog shown)\n" +
					"  2) errorMessage (text: empty if already granted)\n" +
					".\n" +
					"Call IsNotificationPermissionGranted() after user responds to get the result.")
	public void RequestNotificationPermission() {

		if (Build.VERSION.SDK_INT < 33) {
			return;
		}

		try {
			if (activity.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED) {
				return;
			}
		} catch (Exception ignored) {}

		activity.requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1001);

	}

	@SimpleFunction(description =
			"Returns true if POST_NOTIFICATIONS permission is granted.\n" +
					"Always returns true on Android 12 and below.")
	public boolean IsNotificationPermissionGranted() {
		if (Build.VERSION.SDK_INT >= 33) {
			try {
				return activity.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
						== PackageManager.PERMISSION_GRANTED;
			} catch (Exception e) {
				return false;
			}
		}
		return true;
	}

	// ================================================================
	// EVENTS
	// ================================================================

	@SimpleEvent(description =
			"Fired when the FCM token is refreshed automatically by Firebase.\n" +
					"Happens after reinstall, data clear, or token rotation.\n" +
					"Send the new token to your server immediately.\n" +
					"  • token — the new FCM registration token")
	public void TokenReceived(String token) {
		EventDispatcher.dispatchEvent(this, "TokenReceived", token);
	}

	@SimpleEvent(description =
			"Fired when a notification-type FCM message is received.\n" +
					"In foreground: always fires (notification display depends on SetShowForegroundNotifications).\n" +
					"In background/killed: fires only for data-only messages.\n" +
					"  • from       — sender ID\n" +
					"  • messageId  — unique message identifier\n" +
					"  • title      — notification title\n" +
					"  • body       — notification body text\n" +
					"  • dataKeys   — list of extra data payload keys\n" +
					"  • dataValues — list of extra data payload values (same order as keys)")
	public void NotificationReceived(String from, String messageId,
									 String title, String body, YailList dataKeys, YailList dataValues) {
		EventDispatcher.dispatchEvent(this, "NotificationReceived",
				from, messageId, title, body, dataKeys, dataValues);
	}

	@SimpleEvent(description =
			"Fired when a data-only FCM message is received.\n" +
					"Data messages are always delivered to onMessageReceived() regardless of app state.\n" +
					"No notification is shown automatically — your app handles display.\n" +
					"  • from       — sender ID\n" +
					"  • messageId  — unique message identifier\n" +
					"  • dataKeys   — list of data payload keys\n" +
					"  • dataValues — list of data payload values (same order as keys)")
	public void MessageReceived(String from, String messageId,
								YailList dataKeys, YailList dataValues) {
		EventDispatcher.dispatchEvent(this, "MessageReceived",
				from, messageId, dataKeys, dataValues);
	}

	@SimpleEvent(description =
			"Fired when the user taps a notification and the app opens.\n" +
					".\n" +
					"Two scenarios:\n" +
					"  • App killed — call GetLaunchNotification() in Screen1.Initialize\n" +
					"  • App in background — fires automatically via onResume\n" +
					".\n" +
					"  • messageId    — ID of the tapped notification\n" +
					"  • dataKeys     — data payload keys\n" +
					"  • dataValues   — data payload values (same order)")
	public void AppOpenedFromNotification(String messageId, YailList dataKeys, YailList dataValues) {
		EventDispatcher.dispatchEvent(this, "AppOpenedFromNotification",
				messageId, dataKeys, dataValues);
	}

	@SimpleEvent(description =
			"Fired when any FCM operation fails outside of a callback context.\n" +
					"  • operation — name of the method that failed\n" +
					"  • message   — error description")
	public void ErrorOccurred(String operation, String message) {
		EventDispatcher.dispatchEvent(this, "ErrorOccurred", operation, message);
	}

	// ================================================================
	// STATIC DISPATCH — called from MyFCMService
	// ================================================================

	/**
	 * Called by MyFCMService.onNewToken()
	 */
	static void dispatchTokenRefreshed(final String token) {
		FCM ext = getActiveInstance();
		if (ext == null) return;
		ext.prefs.edit().putString(PREF_TOKEN, token).apply();
		ext.mainHandler.post(() -> ext.TokenReceived(token));
	}

	/**
	 * Called by MyFCMService.onMessageReceived() for data-only messages.
	 */
	static void dispatchMessageReceived(
			final String from,
			final String messageId,
			final YailList keys,
			final YailList values) {
		FCM ext = getActiveInstance();
		if (ext == null) return;
		ext.mainHandler.post(() -> ext.MessageReceived(from, messageId, keys, values));
	}

	/**
	 * Called by MyFCMService.onMessageReceived() for notification-type messages.
	 */
	static void dispatchNotificationReceived(
			final String from,
			final String messageId,
			final String title,
			final String body,
			final YailList keys,
			final YailList values) {
		FCM ext = getActiveInstance();
		if (ext == null) return;
		ext.mainHandler.post(() ->
				ext.NotificationReceived(from, messageId, title, body, keys, values));
	}

	/**
	 * Returns whether foreground notifications should be shown.
	 * Called by MyFCMService to check before building a notification.
	 */
	static boolean shouldShowForegroundNotification() {
		FCM ext = getActiveInstance();
		return ext == null || ext.showForegroundNotif;
	}

	/**
	 * Returns the current channel ID for notification building in MyFCMService.
	 */
	static String getChannelId() {
		FCM ext = getActiveInstance();
		return ext != null ? ext.channelId : "fcm_default_channel";
	}

	static String getChannelName() {
		FCM ext = getActiveInstance();
		return ext != null ? ext.channelName : "Push Notifications";
	}

	static String getChannelDescription() {
		FCM ext = getActiveInstance();
		return ext != null ? ext.channelDescription : "";
	}

	private static FCM getActiveInstance() {
		if (activeInstance == null) return null;
		return activeInstance.get();
	}

	// ================================================================
	// LIFECYCLE
	// ================================================================

	@Override
	public void onResume() {
		activeInstance = new WeakReference<>(this);

		Intent intent = activity.getIntent();

		if (intent != null && intent.getExtras() != null) {

			if (intent.hasExtra(FCM.KEY_MESSAGE_ID)
					|| intent.hasExtra("google.message_id")) {

				handleAppOpenedFromNotification(intent);

				// IMPORTANT:
				// clear ONLY after handling
				activity.setIntent(new Intent());
			}
		}
	}

	@Override
	public void onDestroy() {
		if (activeInstance != null && activeInstance.get() == this) {
			activeInstance = null;
		}
	}

	// ================================================================
	// NOTIFICATION TAP HANDLER
	// ================================================================

	private void handleAppOpenedFromNotification(Intent intent) {
		if (intent == null || intent.getExtras() == null) return;

		String messageId = intent.getStringExtra("google.message_id");
		if (messageId == null) messageId = intent.getStringExtra(FCM.KEY_MESSAGE_ID);
		if (messageId == null) messageId = "unknown";

		String targetScreen = intent.getStringExtra(KEY_SCREEN);
		if (targetScreen == null) targetScreen = "";

		List<String> keys = new ArrayList<>();
		List<String> values = new ArrayList<>();

		for (String key : intent.getExtras().keySet()) {
			if (!SYSTEM_KEYS.contains(key)) {
				keys.add(key);

				Object val = intent.getExtras().get(key);
				values.add(val != null ? val.toString() : "");
			}
		}

		YailList keyList = YailList.makeList(keys);
		YailList valueList = YailList.makeList(values);

		Log.d(TAG, "Notification opened: " + messageId);

		// 🔥 IMPORTANT: FIRE EVENT IMMEDIATELY
		FCM ext = getActiveInstance();
		if (ext != null) {
			String finalMessageId = messageId;
			String finalTargetScreen = targetScreen;
			ext.mainHandler.post(() ->
					ext.AppOpenedFromNotification(
							finalMessageId,
							finalTargetScreen,
							keyList,
							valueList
					)
			);
		}
	}

	// ================================================================
	// TOPIC CACHE HELPERS
	// ================================================================

	private boolean isTopicSubscribed(String topic) {
		return prefs.getStringSet(PREF_TOPICS, new HashSet<>()).contains(topic);
	}

	private void addTopicToCache(String topic) {
		Set<String> topics = new HashSet<>(
				prefs.getStringSet(PREF_TOPICS, new HashSet<>()));
		topics.add(topic);
		prefs.edit().putStringSet(PREF_TOPICS, topics).apply();
	}

	private void removeTopicFromCache(String topic) {
		Set<String> topics = new HashSet<>(
				prefs.getStringSet(PREF_TOPICS, new HashSet<>()));
		topics.remove(topic);
		prefs.edit().putStringSet(PREF_TOPICS, topics).apply();
	}

	// ================================================================
	// PRIVATE HELPERS
	// ================================================================

	/**
	 * Validates callback is non-null and has the correct parameter count.
	 */
	private boolean validateCallback(String op, YailProcedure cb, int expected) {
		if (cb == null) {
			dispatchErrorEvent(op, "Callback is null");
			return false;
		}
		if (cb.numArgs() != expected) {
			dispatchErrorEvent(op, "Callback must have exactly " + expected
					+ " parameter(s). Got " + cb.numArgs() + ".");
			return false;
		}
		return true;
	}

	/**
	 * Checks Firebase is initialized.
	 * Fires first callback param as false + last param as error message.
	 * Shape depends on expected arg count.
	 */
	private boolean checkInitialized(String op, YailProcedure cb, int argCount) {
		if (!initialized) {
			String err = "Firebase not initialized. Call Initialize() first.";
			switch (argCount) {
				case 2: fireCallback(cb, Boolean.FALSE, err); break;
				case 3: fireCallback(cb, Boolean.FALSE, "", err); break;
				default: dispatchErrorEvent(op, err); break;
			}
			return false;
		}
		return true;
	}

	/**
	 * Validates callback is non-null and has the correct parameter count.
	 */
	private boolean validateTopicName(String topic, YailProcedure cb) {
		if (topic == null || topic.isEmpty()) {
			fireCallback(cb, Boolean.FALSE, "", "Topic name cannot be empty");
			return false;
		}
		if (!topic.matches("[a-zA-Z0-9\\-_.~%]+")) {
			fireCallback(cb, Boolean.FALSE, topic,
					"Invalid topic name. Must match [a-zA-Z0-9-_.~%]");
			return false;
		}
		if (topic.length() > 900) {
			fireCallback(cb, Boolean.FALSE, topic,
					"Topic name too long (max 900 chars)");
			return false;
		}
		return true;
	}

	/**
	 * Posts a YailProcedure callback on the main thread with variable args.
	 */
	private void fireCallback(final YailProcedure cb, final Object... args) {
		if (cb == null) return;
		mainHandler.post(() -> {
			try {
				cb.call(args);
			} catch (Exception e) {
				Log.e(TAG, "Callback dispatch failed: " + e.getMessage());
				dispatchErrorEvent("Callback", e.getMessage());
			}
		});
	}

	private void dispatchErrorEvent(final String op, final String msg) {
		mainHandler.post(() -> ErrorOccurred(op, msg));
	}
}