package com.hridoy.fcm;

import com.google.appinventor.components.annotations.DesignerComponent;
import com.google.appinventor.components.runtime.AndroidNonvisibleComponent;
import com.google.appinventor.components.runtime.ComponentContainer;
import com.google.appinventor.components.annotations.SimpleFunction;
import com.google.appinventor.components.annotations.SimpleEvent;

import android.app.Activity;
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
import com.google.appinventor.components.runtime.util.YailProcedure;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;

import com.google.appinventor.components.annotations.*;
import com.google.appinventor.components.runtime.*;
import com.google.appinventor.components.runtime.util.YailList;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

@DesignerComponent(
		version = 11,
		versionName = "1.1.0",
		description = "Firebase Cloud Messaging receiver extension. Developed by Hridoy.",
		iconName = "icon.png"
)
public class FCM extends AndroidNonvisibleComponent
		implements Component, OnDestroyListener, OnResumeListener {

	private static final String TAG        = "FCM";
	private static final String PREFS_NAME = "FCMExtPrefs";
	private static final String PREF_TOKEN = "fcm_token";

	static WeakReference<FCM> activeInstance;

	private final Activity        activity;
	private final Handler         mainHandler;
	private final SharedPreferences prefs;
	private boolean initialized = false;

	// ----------------------------------------------------------------
	// Constructor
	// ----------------------------------------------------------------
	public FCM(ComponentContainer container) {
		super(container.$form());
		this.activity    = container.$context() instanceof Activity
				? (Activity) container.$context()
				: container.$form();
		this.mainHandler = new Handler(Looper.getMainLooper());
		this.prefs       = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

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
					".\n" +
					"===============================================================\n" +
					".\n" +
					"Parameters from Firebase Console → Project Settings:\n" +
					"  • apiKey         — Web API key\n" +
					"  • applicationId  — App ID (e.g. 1:123:android:abc)\n" +
					"  • projectId      — Project ID (e.g. my-app-123)\n" +
					"  • senderId       — Sender ID / Cloud Messaging number\n" +
					"  • storageBucket  — Storage bucket (e.g. my-app.appspot.com)\n" +
					".\n" +
					"===============================================================\n" +
					".\n" +
					"Calls the provided callback with 2 parameters:\n" +
					"  1) status (boolean: true = success, false = failure)\n" +
					"  2) errorMessage (text: empty if success)")
	public void Initialize(
			final String apiKey,
			final String applicationId,
			final String projectId,
			final String senderId,
			final String storageBucket,
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
					.setStorageBucket(storageBucket)
					.build();

			if (FirebaseApp.getApps(activity).isEmpty()) {
				FirebaseApp.initializeApp(activity, options);
				Log.d(TAG, "FirebaseApp initialized");
			} else {
				Log.d(TAG, "FirebaseApp already exists, reusing");
			}

			initialized = true;
			fireCallback(callback, Boolean.TRUE, "");

		} catch (Exception e) {
			Log.e(TAG, "Initialize failed", e);
			fireCallback(callback, Boolean.FALSE, e.getMessage());
		}
	}

	// ================================================================
	// GET TOKEN
	// ================================================================

	@SimpleFunction(description =
			"Retrieves the current FCM registration token from Firebase.\n" +
					"Tokens can change over time — always fetch a fresh token\n" +
					"before registering with your server.\n" +
					".\n" +
					"===============================================================\n" +
					".\n" +
					"Calls the provided callback with 2 parameters:\n" +
					"  1) token        (text: the FCM token, empty if failed)\n" +
					"  2) errorMessage (text: empty if success)")
	public void GetToken(final YailProcedure callback) {

		if (!validateCallback("GetToken", callback, 2)) return;
		if (!checkInitialized("GetToken", callback)) return;

		FirebaseMessaging.getInstance().getToken()
				.addOnCompleteListener(new OnCompleteListener<String>() {
					@Override
					public void onComplete(Task<String> task) {
						if (!task.isSuccessful()) {
							String err = task.getException() != null
									? task.getException().getMessage()
									: "Unknown error getting token";
							Log.e(TAG, "GetToken failed: " + err);
							fireCallback(callback, "", err);
							return;
						}
						final String token = task.getResult();
						Log.d(TAG, "Token: " + token);
						prefs.edit().putString(PREF_TOKEN, token).apply();
						fireCallback(callback, token, "");
					}
				});
	}

	// ================================================================
	// GET CACHED TOKEN
	// ================================================================

	@SimpleFunction(description =
			"Returns the last known FCM token from local cache.\n" +
					"Returns an empty string if no token has been fetched yet.\n" +
					"Use GetToken() to always retrieve a fresh token from Firebase.")
	public String GetCachedToken() {
		return prefs.getString(PREF_TOKEN, "");
	}

	// ================================================================
	// SUBSCRIBE TO TOPIC
	// ================================================================

	@SimpleFunction(description =
			"Subscribes this device to an FCM topic.\n" +
					"Topic names must match [a-zA-Z0-9-_.~%] and be under 900 chars.\n" +
					".\n" +
					"===============================================================\n" +
					".\n" +
					"Calls the provided callback with 3 parameters:\n" +
					"  1) status       (boolean: true = success, false = failure)\n" +
					"  2) topic        (text: the topic name)\n" +
					"  3) errorMessage (text: empty if success)")
	public void SubscribeToTopic(final String topic, final YailProcedure callback) {

		if (!validateCallback("SubscribeToTopic", callback, 3)) return;
		if (!checkInitialized("SubscribeToTopic", callback)) return;
		if (!validateTopicName(topic, callback)) return;

		FirebaseMessaging.getInstance().subscribeToTopic(topic)
				.addOnCompleteListener(task -> mainHandler.post(() -> {
					if (task.isSuccessful()) {
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
					".\n" +
					"===============================================================\n" +
					".\n" +
					"Calls the provided callback with 3 parameters:\n" +
					"  1) status       (boolean: true = success, false = failure)\n" +
					"  2) topic        (text: the topic name)\n" +
					"  3) errorMessage (text: empty if success)")
	public void UnsubscribeFromTopic(final String topic, final YailProcedure callback) {

		if (!validateCallback("UnsubscribeFromTopic", callback, 3)) return;
		if (!checkInitialized("UnsubscribeFromTopic", callback)) return;

		FirebaseMessaging.getInstance().unsubscribeFromTopic(topic)
				.addOnCompleteListener(task -> mainHandler.post(() -> {
					if (task.isSuccessful()) {
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
	// DELETE TOKEN
	// ================================================================

	@SimpleFunction(description =
			"Deletes the current FCM registration token.\n" +
					"Use this when a user logs out to stop receiving notifications.\n" +
					"A new token will be generated on the next GetToken() call.\n" +
					".\n" +
					"===============================================================\n" +
					".\n" +
					"Calls the provided callback with 2 parameters:\n" +
					"  1) status       (boolean: true = success, false = failure)\n" +
					"  2) errorMessage (text: empty if success)")
	public void DeleteToken(final YailProcedure callback) {

		if (!validateCallback("DeleteToken", callback, 2)) return;
		if (!checkInitialized("DeleteToken", callback)) return;

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
	// REQUEST NOTIFICATION PERMISSION
	// ================================================================

	@SimpleFunction(description =
			"Requests the POST_NOTIFICATIONS runtime permission on Android 13+.\n" +
					"On Android 12 and below this is a no-op — permission is always granted.\n" +
					"Should be called from a user interaction such as a button click.\n" +
					".\n" +
					"===============================================================\n" +
					".\n" +
					"Calls the provided callback with 2 parameters:\n" +
					"  1) granted      (boolean: true = permission granted)\n" +
					"  2) errorMessage (text: empty if granted)")
	public void RequestNotificationPermission(final YailProcedure callback) {

		if (!validateCallback("RequestNotificationPermission", callback, 2)) return;

		// Below Android 13 — permission not required, always granted
		if (Build.VERSION.SDK_INT < 33) {
			fireCallback(callback, Boolean.TRUE, "");
			return;
		}

		// Already granted — no need to prompt
		try {
			boolean alreadyGranted = activity.checkSelfPermission(
					"android.permission.POST_NOTIFICATIONS"
			) == PackageManager.PERMISSION_GRANTED;

			if (alreadyGranted) {
				fireCallback(callback, Boolean.TRUE, "");
				return;
			}
		} catch (Exception e) {
			// Defensive — should never throw
		}

		// Request the permission
		// Result comes back via onRequestPermissionsResult in the Activity.
		// App Inventor does not expose that callback to extensions directly,
		// so we fire the callback immediately after requesting.
		// The user should call IsNotificationPermissionGranted() after the
		// system dialog is dismissed to confirm the actual result.
		activity.requestPermissions(
				new String[]{"android.permission.POST_NOTIFICATIONS"},
				1001
		);

		// Inform the blocks that the request was dispatched
		fireCallback(callback, Boolean.FALSE,
				"Permission dialog shown — call IsNotificationPermissionGranted() " +
						"after user responds to check the result.");
	}

	// ================================================================
	// IS NOTIFICATION PERMISSION GRANTED
	// ================================================================

	@SimpleFunction(description =
			"Returns true if the POST_NOTIFICATIONS permission is granted.\n" +
					"Always returns true on Android 12 and below.\n" +
					"Call this after RequestNotificationPermission() to check the result.")
	public boolean IsNotificationPermissionGranted() {
		if (Build.VERSION.SDK_INT >= 33) {
			try {
				return activity.checkSelfPermission(
						"android.permission.POST_NOTIFICATIONS"
				) == PackageManager.PERMISSION_GRANTED;
			} catch (Exception e) {
				return false;
			}
		}
		return true;
	}

	// ================================================================
	// EVENTS — Message delivery (service → extension bridge)
	// These remain as events because they are pushed asynchronously
	// by MyFCMService and are not tied to a specific user action.
	// ================================================================

	@SimpleEvent(description =
			"Fired when the FCM token is refreshed by Firebase automatically.\n" +
					"This can happen after app reinstall, data clear, or token rotation.\n" +
					"Send the new token to your server immediately.")
	public void TokenReceived(String token) {
		EventDispatcher.dispatchEvent(this, "TokenReceived", token);
	}

	@SimpleEvent(description =
			"Fired when a data message arrives while the app is in the foreground,\n" +
					"or when a background data-only message is delivered.\n" +
					"  • from       — sender ID\n" +
					"  • messageId  — unique message ID\n" +
					"  • dataKeys   — list of data payload keys\n" +
					"  • dataValues — list of data payload values (same order as keys)")
	public void MessageReceived(String from, String messageId,
								YailList dataKeys, YailList dataValues) {
		EventDispatcher.dispatchEvent(this, "MessageReceived",
				from, messageId, dataKeys, dataValues);
	}

	@SimpleEvent(description =
			"Fired when the user taps a notification generated by this extension.\n" +
					"  • messageId  — ID of the tapped message\n" +
					"  • dataKeys   — list of data payload keys\n" +
					"  • dataValues — list of data payload values")
	public void NotificationClicked(String messageId,
									YailList dataKeys, YailList dataValues) {
		EventDispatcher.dispatchEvent(this, "NotificationClicked",
				messageId, dataKeys, dataValues);
	}

	@SimpleEvent(description =
			"Fired when any internal FCM operation fails outside of a callback context.\n" +
					"  • operation — name of the method that failed\n" +
					"  • message   — error description")
	public void ErrorOccurred(String operation, String message) {
		EventDispatcher.dispatchEvent(this, "ErrorOccurred", operation, message);
	}

	// ================================================================
	// STATIC DISPATCH — called from MyFCMService
	// ================================================================

	static void dispatchTokenRefreshed(final String token) {
		if (activeInstance == null) return;
		final FCM ext = activeInstance.get();
		if (ext == null) return;
		ext.prefs.edit().putString(PREF_TOKEN, token).apply();
		ext.mainHandler.post(() -> ext.TokenReceived(token));
	}

	static void dispatchMessageReceived(
			final String from,
			final String messageId,
			final YailList keys,
			final YailList values) {
		if (activeInstance == null) return;
		final FCM ext = activeInstance.get();
		if (ext == null) return;
		ext.mainHandler.post(() -> ext.MessageReceived(from, messageId, keys, values));
	}

	// ================================================================
	// LIFECYCLE
	// ================================================================

	@Override
	public void onDestroy() {
		if (activeInstance != null && activeInstance.get() == this) {
			activeInstance = null;
		}
	}

	@Override
	public void onResume() {
		activeInstance = new WeakReference<>(this);
		Intent launchIntent = activity.getIntent();
		if (launchIntent != null && launchIntent.hasExtra("fcm_message_id")) {
			handleNotificationClick(launchIntent);
			activity.setIntent(new Intent());
		}
	}

	// ================================================================
	// PRIVATE HELPERS
	// ================================================================

	/**
	 * Validates that callback is non-null and has exactly the expected
	 * number of parameters. Fires ErrorOccurred if invalid.
	 * Returns true if valid.
	 */
	private boolean validateCallback(String operation, YailProcedure callback, int expectedArgs) {
		if (callback == null) {
			dispatchErrorEvent(operation, "Callback is null");
			return false;
		}
		if (callback.numArgs() != expectedArgs) {
			dispatchErrorEvent(operation,
					"Callback must have exactly " + expectedArgs + " parameter(s). " +
							"Got " + callback.numArgs() + ".");
			return false;
		}
		return true;
	}

	/**
	 * Checks Firebase is initialized. Fires callback with error if not.
	 * Returns true if initialized.
	 */
	private boolean checkInitialized(String operation, YailProcedure callback) {
		if (!initialized) {
			// Fire through callback so blocks can handle it inline
			// Use varargs fireCallback — 2-param error shape
			mainHandler.post(() -> {
				try {
					// Build args: first param false/empty, last param = error message
					// We don't know the shape here so fire ErrorOccurred event instead
					ErrorOccurred(operation,
							"Firebase not initialized. Call Initialize() first.");
				} catch (Exception ignored) {}
			});
			return false;
		}
		return true;
	}

	/**
	 * Validates topic name format. Fires callback with error if invalid.
	 */
	private boolean validateTopicName(String topic, YailProcedure callback) {
		if (topic == null || topic.isEmpty()) {
			fireCallback(callback, Boolean.FALSE, topic, "Topic name cannot be empty");
			return false;
		}
		if (!topic.matches("[a-zA-Z0-9\\-_.~%]+")) {
			fireCallback(callback, Boolean.FALSE, topic,
					"Invalid topic name. Must match [a-zA-Z0-9-_.~%]");
			return false;
		}
		if (topic.length() > 900) {
			fireCallback(callback, Boolean.FALSE, topic,
					"Topic name too long (max 900 chars)");
			return false;
		}
		return true;
	}

	/**
	 * Fires a YailProcedure callback on the main thread with variable args.
	 * Matches App Inventor's anonymous block calling convention.
	 */
	private void fireCallback(final YailProcedure callback, final Object... args) {
		if (callback == null) return;
		mainHandler.post(() -> {
			try {
				callback.call(args);
			} catch (Exception e) {
				Log.e(TAG, "Callback dispatch failed: " + e.getMessage());
				dispatchErrorEvent("Callback", e.getMessage());
			}
		});
	}

	/**
	 * Fires ErrorOccurred event — used when no callback context is available.
	 */
	private void dispatchErrorEvent(final String operation, final String message) {
		mainHandler.post(() -> ErrorOccurred(operation, message));
	}

	/**
	 * Handles notification tap after process death — reads FCM data from Intent.
	 */
	private void handleNotificationClick(Intent intent) {
		String messageId = intent.getStringExtra("fcm_message_id");
		if (messageId == null) return;

		List<String> keys   = new ArrayList<>();
		List<String> values = new ArrayList<>();

		for (String key : intent.getExtras().keySet()) {
			if (!key.equals("fcm_message_id")) {
				keys.add(key);
				Object val = intent.getExtras().get(key);
				values.add(val != null ? val.toString() : "");
			}
		}

		final YailList keyList   = YailList.makeList(keys);
		final YailList valueList = YailList.makeList(values);
		final String   finalId   = messageId;

		mainHandler.post(() -> NotificationClicked(finalId, keyList, valueList));
	}
}