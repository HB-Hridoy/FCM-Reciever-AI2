package com.hridoy.fcm;

import com.google.appinventor.components.annotations.*;
import com.google.appinventor.components.runtime.*;
import com.google.appinventor.components.runtime.util.YailDictionary;
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

import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;

import java.lang.ref.WeakReference;
import java.util.*;

@DesignerComponent(
		version = 82,
		versionName = "1.0.2",
		description = "Firebase Cloud Messaging receiver extension. Developed by Hridoy.",
		iconName = "icon.png"
)
public class FCM extends AndroidNonvisibleComponent
		implements Component, OnDestroyListener, OnResumeListener, OnNewIntentListener {

	private static final String TAG         = "FCM";
	private static final String PREFS_NAME  = "FCMExtPrefs";
	private static final String PREF_TOKEN  = "fcm_token";
	private static final String PREF_TOPICS = "fcm_subscribed_topics";

	static final String KEY_TITLE      = "fcm_title";
	static final String KEY_BODY       = "fcm_body";
	static final String KEY_IMAGE      = "fcm_image";
	static final String KEY_SMALL_ICON = "fcm_small_icon";
	static final String KEY_AVATAR = "fcm_avatar";
	static final String KEY_MESSAGE_ID = "fcm_message_id";

	// Internal FCM extras added by the system — filtered from data payloads
	private static final List<String> SYSTEM_KEYS = Arrays.asList(
			"google.message_id", "google.sent_time", "google.ttl",
			"google.original_message_id", "collapse_key", "from",
			KEY_MESSAGE_ID, "gcm.notification.body", "gcm.notification.title",
			"gcm.notification.image", "android.support.content.wakelockid",
			"androidx.content.wakelockid",
			KEY_TITLE, KEY_BODY, KEY_IMAGE, KEY_SMALL_ICON, KEY_AVATAR
	);

	// Notification channel config
	private String  channelId          = "fcm_default_channel";
	private String  channelName        = "Push Notifications";
	private String  channelDescription = "App push notifications";
	private boolean showForegroundNotif = true;

	// Static bridge — allows MyFCMService to reach the live extension instance
	static WeakReference<FCM> activeInstance;

	private final Activity          activity;
	private final Handler           mainHandler;
	private final SharedPreferences prefs;
	private boolean initialized = false;

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

		container.$form().registerForOnDestroy(this);
		container.$form().registerForOnResume(this);
		container.$form().registerForOnNewIntent(this);
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
			}

			initialized = true;
			fireCallback(callback, Boolean.TRUE, "");

		} catch (Exception e) {
			Log.e(TAG, "Initialize failed", e);
			fireCallback(callback, Boolean.FALSE, e.getMessage());
		}
	}

	// ================================================================
	// GET LAUNCH NOTIFICATION
	// ================================================================

	@SimpleFunction(description =
			"Call this in Screen1.Initialize to check if the app was opened\n" +
					"by tapping a notification while the app was killed or in background.\n" +
					".\n===============================================================\n.\n" +
					"Reads the current launch Intent directly — no static storage,\n" +
					"works correctly with multiple notifications.\n" +
					".\n" +
					"If the app was opened from a notification tap, fires\n" +
					"AppOpenedFromNotification immediately.\n" +
					"If the app was opened normally, does nothing.\n" +
					".\n" +
					"Recommended call order in Screen1.Initialize:\n" +
					"  1. call FCM.Initialize(...)\n" +
					"  2. call FCM.GetLaunchNotification()\n" +
					"  3. call FCM.GetToken(...)")
	public void GetLaunchNotification() {
		Intent intent = activity.getIntent();

		if (intent == null || intent.getExtras() == null) return;

		// Check if this Intent carries FCM notification tap data
		boolean hasFcmData = intent.hasExtra(KEY_MESSAGE_ID)
				|| intent.hasExtra("google.message_id");

		if (!hasFcmData) return;

		// Read and dispatch
		dispatchFromIntent(intent);

		// Clear the Intent so re-entering this screen doesn't re-fire
		activity.setIntent(new Intent());
	}

	// ================================================================
	// NOTIFICATION CHANNEL
	// ================================================================

	@SimpleFunction(description =
			"Configures the Android notification channel for all FCM notifications.\n" +
					"Call before the first notification is displayed.\n" +
					"No-op on Android 7 and below.\n" +
					".\n===============================================================\n.\n" +
					"Parameters:\n" +
					"  • channelId          — unique ID (e.g. 'my_channel')\n" +
					"  • channelName        — name shown in system settings\n" +
					"  • channelDescription — description shown in system settings")
	public void SetNotificationChannel(
			String channelId, String channelName, String channelDescription) {
		this.channelId          = channelId;
		this.channelName        = channelName;
		this.channelDescription = channelDescription;

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			NotificationManager mgr =
					(NotificationManager) activity.getSystemService(Context.NOTIFICATION_SERVICE);
			NotificationChannel ch = new NotificationChannel(
					channelId, channelName, NotificationManager.IMPORTANCE_HIGH);
			ch.setDescription(channelDescription);
			mgr.createNotificationChannel(ch);
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
					"Always fetch fresh before sending to your server.\n" +
					".\n===============================================================\n.\n" +
					"Callback parameters:\n" +
					"  1) token        (text: FCM token, empty if failed)\n" +
					"  2) errorMessage (text: empty if success)")
	public void GetToken(final YailProcedure callback) {
		if (!validateCallback("GetToken", callback, 2)) return;
		if (!checkInitialized("GetToken", callback, 2)) return;

		FirebaseMessaging.getInstance().getToken()
				.addOnCompleteListener(task -> {
					if (!task.isSuccessful()) {
						String err = task.getException() != null
								? task.getException().getMessage() : "Unknown error";
						fireCallback(callback, "", err);
						return;
					}
					String token = task.getResult();
					prefs.edit().putString(PREF_TOKEN, token).apply();
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
					"  3) errorMessage (text: empty if success)")
	public void SubscribeToTopic(final String topic, final YailProcedure callback) {
		if (!validateCallback("SubscribeToTopic", callback, 3)) return;
		if (!checkInitialized("SubscribeToTopic", callback, 3)) return;
		if (!validateTopicName(topic, callback)) return;

		if (isTopicSubscribed(topic)) {
			fireCallback(callback, Boolean.FALSE, topic, "Already subscribed to: " + topic);
			return;
		}

		FirebaseMessaging.getInstance().subscribeToTopic(topic)
				.addOnCompleteListener(task -> mainHandler.post(() -> {
					if (task.isSuccessful()) {
						addTopicToCache(topic);
						fireCallback(callback, Boolean.TRUE, topic, "");
					} else {
						String err = task.getException() != null
								? task.getException().getMessage() : "Unknown error";
						fireCallback(callback, Boolean.FALSE, topic, err);
					}
				}));
	}

	// ================================================================
	// UNSUBSCRIBE FROM TOPIC
	// ================================================================

	@SimpleFunction(description =
			"Unsubscribes this device from an FCM topic.\n" +
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
						fireCallback(callback, Boolean.TRUE, topic, "");
					} else {
						String err = task.getException() != null
								? task.getException().getMessage() : "Unknown error";
						fireCallback(callback, Boolean.FALSE, topic, err);
					}
				}));
	}

	// ================================================================
	// SUBSCRIBED TOPICS
	// ================================================================

	@SimpleFunction(description =
			"Returns the list of topics this device is currently subscribed to.\n" +
					"Reads from local cache — no network request.\n" +
					"Returns empty list if not subscribed to any topics.")
	public YailList SubscribedTopics() {
		Set<String> topics = prefs.getStringSet(PREF_TOPICS, new HashSet<>());
		return YailList.makeList(new ArrayList<>(topics));
	}

	// ================================================================
	// DELETE TOKEN
	// ================================================================

	@SimpleFunction(description =
			"Deletes the current FCM registration token.\n" +
					"Use on user logout to stop receiving targeted notifications.\n" +
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
						fireCallback(callback, Boolean.TRUE, "");
					} else {
						String err = task.getException() != null
								? task.getException().getMessage() : "Unknown error";
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
			"Fired when the FCM token is automatically refreshed by Firebase.\n" +
					"Send the new token to your server immediately.\n" +
					"  • token — the new FCM registration token")
	public void TokenReceived(String token) {
		EventDispatcher.dispatchEvent(this, "TokenReceived", token);
	}

	@SimpleEvent(description =
			"Fired when a notification-type FCM message arrives.\n" +
					"Always fires in foreground.\n" +
					"  • from       — sender ID\n" +
					"  • messageId  — unique message identifier\n" +
					"  • title      — notification title\n" +
					"  • body       — notification body text\n" +
					"  • dataKeys   — list of extra data payload keys\n" +
					"  • dataValues — list of extra data payload values (same order as keys)")
	public void NotificationReceived(String from, String messageId,
									 String title, String body, YailDictionary data) {
		EventDispatcher.dispatchEvent(this, "NotificationReceived",
				from, messageId, title, body, data);
	}

	@SimpleEvent(description =
			"Fired when a data-only FCM message arrives.\n" +
					"Delivered regardless of app state. No notification is shown.\n" +
					"  • from       — sender ID\n" +
					"  • messageId  — unique message identifier\n" +
					"  • dataKeys   — list of data payload keys\n" +
					"  • dataValues — list of data payload values (same order as keys)")
	public void MessageReceived(String from, String messageId,
								YailDictionary data) {
		EventDispatcher.dispatchEvent(this, "MessageReceived",
				from, messageId, data);
	}

	@SimpleEvent(description =
			"Fired when the user taps a notification and the app opens.\n" +
					".\n" +
					"Two scenarios:\n" +
					"  • App killed — call GetLaunchNotification() in Screen1.Initialize\n" +
					"  • App in background — fires automatically via onResume\n" +
					".\n" +
					"  • messageId    — ID of the tapped notification\n" +
					"  • data     — data payload ")
	public void AppOpenedFromNotification(String messageId, YailDictionary data) {
		EventDispatcher.dispatchEvent(this, "AppOpenedFromNotification",
				messageId, data);
	}

	@SimpleEvent(description =
			"Fired when any FCM operation fails outside of a callback context.\n" +
					"  • operation — name of the method that failed\n" +
					"  • message   — error description")
	public void ErrorOccurred(String operation, String message) {
		EventDispatcher.dispatchEvent(this, "ErrorOccurred", operation, message);
	}

	// ================================================================
	// STATIC DISPATCH — called from FCMService
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
			final YailDictionary dataDict) {
		FCM ext = getActiveInstance();
		if (ext == null) return;
		ext.mainHandler.post(() -> ext.MessageReceived(from, messageId, dataDict));
	}

	/**
	 * Called by MyFCMService.onMessageReceived() for notification-type messages.
	 */
	static void dispatchNotificationReceived(
			final String from,
			final String messageId,
			final String title,
			final String body,
			final YailDictionary dataDict) {
		FCM ext = getActiveInstance();
		if (ext == null) return;
		ext.mainHandler.post(() ->
				ext.NotificationReceived(from, messageId, title, body, dataDict));
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
	}

	@Override
	public void onDestroy() {
		if (activeInstance != null && activeInstance.get() == this) {
			activeInstance = null;
		}
	}

	@Override
	public void onNewIntent(Intent intent) {
		// Called when app is in background/foreground and user taps notification.
		// Android delivers the new Intent here instead of creating a new Activity.
		if (intent == null || intent.getExtras() == null) return;

		boolean hasFcmData = intent.hasExtra(KEY_MESSAGE_ID)
				|| intent.hasExtra("google.message_id");

		if (!hasFcmData) return;

		// Update the Activity's intent so getIntent() is also consistent
		activity.setIntent(intent);

		dispatchFromIntent(intent);

		// Clear after dispatch
		activity.setIntent(new Intent());
	}

	// ================================================================
	// INTENT READER — shared by GetLaunchNotification() and onNewIntent()
	// ================================================================

	/**
	 * Reads FCM data from an Intent and dispatches AppOpenedFromNotification.
	 * Used by both GetLaunchNotification() (cold start) and
	 * onResume() (background → foreground).
	 */
	private void dispatchFromIntent(Intent intent) {
		String messageId = intent.getStringExtra("google.message_id");
		if (messageId == null) messageId = intent.getStringExtra(KEY_MESSAGE_ID);
		if (messageId == null) messageId = "unknown";

		YailDictionary dataDict = new YailDictionary();
		for (String key : intent.getExtras().keySet()) {
			if (!SYSTEM_KEYS.contains(key)) {
				Object val = intent.getExtras().get(key);
				dataDict.put(key, val != null ? val.toString() : "");
			}
		}

		final YailDictionary finalDict  = dataDict;

		final String   finalId     = messageId;

		Log.d(TAG, "AppOpenedFromNotification: " + finalId );

		mainHandler.post(() ->
				AppOpenedFromNotification(finalId, finalDict));
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