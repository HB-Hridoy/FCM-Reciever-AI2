<div align="center">
<h1><kbd><img width="333" height="151" alt="Firebase-Cloud-Messaging" src="https://github.com/user-attachments/assets/1c030a13-5f4c-4a92-80d3-a9f752cee1ad" /></kbd></h1>
An extension for MIT App Inventor 2.<br>
Firebase Cloud Messaging receiver extension. Developed by Hridoy.
</div>


## 📝 Specifications
* **
📦 **Package:** com.hridoy.fcm  
💾 **Size:** 973 KB  
⚙️ **Version:** 1.0.2  
📱 **Minimum API Level:** 14  
📅 **Updated On:** 22-05-2026 SAST  
💻 **Built & documented using:** [FAST](https://community.appinventor.mit.edu/t/fast-an-efficient-way-to-build-publish-extensions/129103?u=jewel) <small><mark>v6.1.0</mark></small>

---

## All Blocks
<img width="1876" height="723" alt="blocks" src="https://github.com/user-attachments/assets/226302b0-6583-4ca9-8529-3dae8269ddf3" />

---

## Events

<details>
<summary><kbd>Events (5)</kbd></summary>

### 1. TokenReceived
<img width="240" height="85" alt="TokenReceived" src="https://github.com/user-attachments/assets/77d81e3f-e5f1-4bc4-a6d6-bfb2b9adc4ba" />

Fired when the FCM token is automatically refreshed by Firebase.
Send the new token to your server immediately.

| Parameter | Type | Desciption                 |
|-----------|------|----------------------------|
| token     | text | new FCM registration token |

---

### 2. NotificationReceived
<img width="336" height="85" alt="NotificationReceived" src="https://github.com/user-attachments/assets/d9ef3447-203e-410d-a378-1fd73848ccc1" />

Fired when a notification-type FCM message arrives.
Always fires in foreground.

| Parameter  | Type | Description                                            |
|------------|------|--------------------------------------------------------|
| from       | text | sender ID                                              |
| messageId  | text | unique message identifier                              |
| title      | text | notification title                                     |
| body       | text | notification body text                                 |
| data   | dictonary | extra data payload in dictonary                        |

---

### 3. MessageReceived
<img width="259" height="85" alt="MessageReceived" src="https://github.com/user-attachments/assets/f2ec8d8a-a0f4-4864-bfbb-24853634ce54" />



Fired when a data-only FCM message arrives.
Delivered regardless of app state. No notification is shown.

| Parameter  | Type | Description                                      |
|------------|------|--------------------------------------------------|
| from       | text | sender ID                                        |
| messageId  | text | unique message identifier                        |
| data   | dictonary | extra data payload in dictonary                        |

---

### 4. AppOpenedFromNotification
<img width="324" height="85" alt="AppOpenedFromNotification" src="https://github.com/user-attachments/assets/bc1135f4-d6e1-49b1-b5d1-369d0bac6939" />


Fired when the user taps a notification and the app opens.

Two scenarios:  
• App killed — call GetLaunchNotification() in Screen.Initialize  
• App in background — fires automatically via onResume


| Parameter  | Type | Description                      |
|------------|------|----------------------------------|
| messageId  | text | ID of the tapped notification    |
| data   | dictonary | extra data payload in dictonary                        |

---

### 5. ErrorOccurred
<img width="231" height="85" alt="ErrorOccurred" src="https://github.com/user-attachments/assets/281d624e-d195-4069-9e1c-36850e2a6089" />

Fired when any FCM operation fails outside of a callback context.

| Parameter | Type | Description                    |
|-----------|------|--------------------------------|
| operation | text | name of the method that failed |
| message   | text | error description              |

</details>

---

## Methods

<details>
<summary><kbd>Methods (11)</kbd></summary>

### 1. Initialize
<img width="188" height="155" alt="Initialize" src="https://github.com/user-attachments/assets/fd2c0df5-a0be-49b9-9009-da3273c600f2" />

Initializes Firebase with your project credentials.
Call this once at app startup before any other FCM method.

| Parameter     | Type      | Description                                                                                                      |
|---------------|-----------|------------------------------------------------------------------------------------------------------------------|
| apiKey        | text      | Web API key                                                                                                      |
| applicationId | text      | App ID (e.g. 1:123:android:abc)                                                                                  |
| projectId     | text      | Project ID (e.g. my-app-123)                                                                                     |
| senderId      | text      | Sender ID / Cloud Messaging number                                                                               |
| callback      | procedure | Need 2 param: <br>• status (boolean: true = success, false = failure)<br>• errorMessage (text: empty if success) |

---

### 2. GetLaunchNotification
<img width="272" height="30" alt="GetLaunchNotification" src="https://github.com/user-attachments/assets/3bf17740-d494-4977-95be-cd7abbcdd705" />

Call this in Screen.Initialize to check if the app was opened
by tapping a notification while the app was killed or in background.

If the app was opened from a notification tap, fires
AppOpenedFromNotification immediately.
If the app was opened normally, does nothing.

Recommended call order in Screen.Initialize:

1. call FCM.Initialize(...)

2. In Initialize callback (status = true):
   → call FCM.GetLaunchNotification()
   → call FCM.GetToken(...)

---

### 3. SetNotificationChannel
<img width="285" height="105" alt="SetNotificationChannel" src="https://github.com/user-attachments/assets/e4d86eeb-3d5d-42bb-a63d-b29529527468" />

Configures the Android notification channel for all FCM notifications.
Call before the first notification is displayed.
No-op on Android 7 and below.

| Parameter          | Type | Description                          |
|--------------------|------|--------------------------------------|
| channelId          | text | unique ID (e.g. 'my_channel')        |
| channelName        | text | name shown in system settings        |
| channelDescription | text | description shown in system settings |

---

### 4. GetToken
<img width="199" height="55" alt="GetToken" src="https://github.com/user-attachments/assets/c8485b2d-fca5-4a2a-b538-5ade7b733270" />

Retrieves the current FCM registration token from Firebase.
Always fetch fresh before sending to your server.

| Parameter | Type      | Description                                                                                                    |
|-----------|-----------|----------------------------------------------------------------------------------------------------------------|
| callback  | procedure | Need 2 parameters:<br>1) token  (text: FCM token, empty if failed)<br>2) errorMessage (text: empty if success) |

---

### 5. GetCachedToken
<img width="250" height="26" alt="GetCachedToken" src="https://github.com/user-attachments/assets/96d587bd-1bc7-486d-8a50-7c4a2ccff015" />

Returns the last known FCM token from local cache.
Returns empty string if no token has been fetched yet.
Use GetToken() to retrieve a fresh token from Firebase.

* Return type: `text`

---

### 6. SubscribeToTopic
<img width="255" height="80" alt="SubscribeToTopic" src="https://github.com/user-attachments/assets/0ff27de3-338a-4668-8cfa-5d21c9131a29" />

Subscribes this device to an FCM topic.
Checks local cache first — skips Firebase call if already subscribed.
Topic names must match [a-zA-Z0-9-_.~%] and be under 900 chars.

| Parameter | Type      | Description                                                                                                                                                      |
|-----------|-----------|------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| topic     | text      | the topic name                                                                                                                                                   |
| callback  | procedure | Need 3 parameters:<br>1) status (boolean: true = success, false = failure)<br>2) topic        (text: the topic name)<br>3) errorMessage (text: empty if success) |

---

### 7. UnsubscribeFromTopic
<img width="288" height="80" alt="UnsubscribeFromTopic" src="https://github.com/user-attachments/assets/499cb427-00a3-4aba-a9dd-faa8ed383641" />

Unsubscribes this device from an FCM topic.

| Parameter | Type      | Description                                                                                                                                                      |
|-----------|-----------|------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| topic     | text      | the topic name                                                                                                                                                   |
| callback  | procedure | Need 3 parameters:<br>1) status (boolean: true = success, false = failure)<br>2) topic        (text: the topic name)<br>3) errorMessage (text: empty if success) |

---

### 8. SubscribedTopics
<img width="252" height="26" alt="SubscribedTopics" src="https://github.com/user-attachments/assets/381dbc9a-3f9f-4868-a0a6-d9372b6707d1" />

Returns the list of topics this device is currently subscribed to.
Reads from local cache. No network request.
Returns empty list if not subscribed to any topics.

* Return type: `list`

---

### 9. DeleteToken
<img width="218" height="55" alt="DeleteToken" src="https://github.com/user-attachments/assets/09ede784-5e5b-42a8-b951-0ec6dc8e8361" />

Deletes the current FCM registration token.
Use on user logout to stop receiving targeted notifications.
A new token is generated on the next GetToken() call.


| Parameter | Type      | Description                                                                                                            |
|-----------|-----------|------------------------------------------------------------------------------------------------------------------------|
| callback  | procedure | Need 2 parameters:<br>1) status (boolean: true = success, false = failure)<br>2) errorMessage (text: empty if success) |

---

### 10. RequestNotificationPermission
<img width="328" height="30" alt="RequestNotificationPermission" src="https://github.com/user-attachments/assets/501364ff-ae51-43de-98ac-1ec0d4a72f20" />

Requests POST_NOTIFICATIONS runtime permission on Android 13+.
No-op on Android 12 and below.
---

### 11. IsNotificationPermissionGranted
<img width="346" height="26" alt="IsNotificationPermissionGranted" src="https://github.com/user-attachments/assets/3bf33d4c-dedd-4a5e-abe8-b450daa9457c" />

Returns true if POST_NOTIFICATIONS permission is granted.
Always returns true on Android 12 and below.

* Return type: `boolean`

</details>

---

## Properties

<details>
<summary><kbd>Properties (1)</kbd></summary>

### 1. ShowForegroundNotifications
<img width="434" height="30" alt="ShowForegroundNotifications" src="https://github.com/user-attachments/assets/fb773b4d-cf8f-40e4-bff7-fd40ea78ce08" />
<img width="312" height="26" alt="ShowForegroundNotifications" src="https://github.com/user-attachments/assets/5d0d5574-a5dd-469a-bcbe-a025f44150ff" />

Controls whether notifications are shown when the app is in foreground.
Resets to true each session — call in Screen.Initialize to change.

• true = show notification (default)  
• false = suppress, only fire NotificationReceived event

* Input type: `boolean`

</details>


