# ==============================================================================
# 1. CORE COMPILATION & REPACKAGING ARCHITECTURE
# ==============================================================================

# Repackages general third-party dependency libraries to prevent workspace collisions
-repackageclasses %packageName%.repacked

# Exclude your personal development package scope from name shuffling
-keeppackagenames com.hridoy.fcm

# CRITICAL EXCLUSION: Prevent moving Firebase, its sub-packages (.iid, .messaging),
# or GMS core basement into the .repacked folder.
-keeppackagenames com.google.android.gms.common
-keeppackagenames com.google.firebase
-keeppackagenames com.google.firebase.**

-android

# CRITICAL: Completely disable optimization to bypass strict syntax checks.
-dontoptimize

-allowaccessmodification
-dontskipnonpubliclibraryclasses
-dontskipnonpubliclibraryclassmembers

# ==============================================================================
# 2. PROGUARD DEPENDENCY SUPPRESSIONS (FIXES COMPILATION CRASHES)
# ==============================================================================

# Suppress warnings for missing external SDK dependencies (Google Play Services / Tasks)
-dontwarn com.google.android.gms.**
-dontwarn com.google.firebase.**
-dontwarn com.google.auto.value.**
-dontwarn androidx.annotation.**
-dontwarn kotlinx.coroutines.**

# CRITICAL: Suppress structural and duplicate class collisions for Protobuf.
# This prevents ProGuard from comparing AI2's old protobuf-java-3.0.0.jar with your protobuf-javalite.
-dontwarn com.google.protobuf.**

# Suppress structural warnings from App Inventor's built-in cloud-token components
-dontwarn com.google.appinventor.components.runtime.chatbot.**
-dontwarn com.google.appinventor.components.runtime.imagebot.**
-dontwarn com.google.appinventor.components.runtime.translate.**

# Suppress internal system layer classes
-dontwarn android.security.NetworkSecurityPolicy
-dontwarn android.app.Notification
-dontwarn sun.misc.**
-dontwarn libcore.io.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.jspecify.nullness.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
-dontwarn java.lang.instrument.**

# ==============================================================================
# 3. MIT APP INVENTOR INTERACTION SAFETY WALL
# ==============================================================================

# Explicitly tell ProGuard that App Inventor components are safe "program" targets
-keep class com.google.appinventor.components.runtime.util.YailDictionary { *; }
-keep class com.google.appinventor.components.runtime.util.YailList { *; }
-keep class com.google.appinventor.components.runtime.util.YailProcedure { *; }
-keep class com.google.appinventor.components.runtime.EventDispatcher { *; }
-keep class com.google.appinventor.components.runtime.Component { *; }
-keep class com.google.appinventor.components.runtime.AndroidNonvisibleComponent { *; }
-keep class com.google.appinventor.components.runtime.ComponentContainer { *; }
-keep class com.google.appinventor.components.runtime.Form { *; }

# Protect internal thread hooks from structural transformation
-keep class com.google.appinventor.components.runtime.util.AsynchUtil { *; }

# Protect core lifecycle callback registrations from being flattened
-keep interface com.google.appinventor.components.runtime.OnDestroyListener { *; }
-keep interface com.google.appinventor.components.runtime.OnResumeListener { *; }
-keep interface com.google.appinventor.components.runtime.OnNewIntentListener { *; }

# ==============================================================================
# 4. GMS / SAFEPARCELABLE REFLECTION PROTECTION
# ==============================================================================

-keepclassmembers public class com.google.android.gms.common.internal.safeparcel.SafeParcelable {
    public static final *** NULL;
}
-keep class com.google.android.gms.common.internal.ReflectedParcelable
-keepnames class * implements com.google.android.gms.common.internal.ReflectedParcelable
-keepclassmembers class * implements android.os.Parcelable {
    public static final *** CREATOR;
}
-keep @interface com.google.android.gms.common.annotation.KeepName
-keepnames @com.google.android.gms.common.annotation.KeepName class *
-keepclassmembernames class * {
    @com.google.android.gms.common.annotation.KeepName *;
}
-keep @interface com.google.android.gms.common.util.DynamiteApi
-keep @com.google.android.gms.common.util.DynamiteApi public class * {
    public <fields>;
    public <methods>;
}
-keepclassmembers class com.google.android.gms.common.api.internal.BasePendingResult {
    com.google.android.gms.common.api.internal.BasePendingResult$ReleasableResultGuardian mResultGuardian;
}

# ==============================================================================
# 5. ANDROIDX KEEP ANNOTATIONS
# ==============================================================================

-keep @interface android.support.annotation.Keep
-keep @androidx.annotation.Keep class *
-keepclasseswithmembers class * {
    @androidx.annotation.Keep <fields>;
}
-keepclasseswithmembers class * {
    @androidx.annotation.Keep <methods>;
}

# ==============================================================================
# 6. FIREBASE DISCOVERY & RECEIVER SERVICE CONFIGURATION
# ==============================================================================

-keep class * implements com.google.firebase.components.ComponentRegistrar { *; }
-keep interface com.google.firebase.components.ComponentRegistrar
-keep class com.google.firebase.components.ComponentDiscoveryService { *; }
-keep class com.google.firebase.messaging.FirebaseMessagingRegistrar { *; }
-keep class com.google.firebase.installations.FirebaseInstallationsRegistrar { *; }

# CRITICAL MANIFEST LOCK: Do not touch, rename, or repackage the hardware receivers
-keep class com.google.firebase.iid.FirebaseInstanceIdReceiver { *; }
-keep class com.google.firebase.messaging.FirebaseMessagingService { *; }

-keep class com.google.firebase.messaging.** { *; }
-keep class com.google.firebase.installations.** { *; }
-keep class com.google.firebase.FirebaseApp { *; }
-keep class com.google.firebase.FirebaseOptions { *; }
-keep class com.google.firebase.FirebaseOptions$Builder { *; }

# ==============================================================================
# 7. YOUR CUSTOM FCM EXTENSION LAYER
# ==============================================================================

# Protect your core extension containers from optimization adjustments or renaming
-keep class com.hridoy.fcm.FCM { *; }
-keep class com.hridoy.fcm.FCMService { *; }

-keepclassmembers class com.hridoy.fcm.FCM {
    public <init>(com.google.appinventor.components.runtime.ComponentContainer);
    @com.google.appinventor.components.annotations.SimpleFunction *;
    @com.google.appinventor.components.annotations.SimpleProperty *;
    @com.google.appinventor.components.annotations.SimpleEvent *;
    static void dispatchTokenRefreshed(java.lang.String);
    static void dispatchMessageReceived(java.lang.String, java.lang.String, com.google.appinventor.components.runtime.util.YailDictionary);
    static void dispatchNotificationReceived(java.lang.String, java.lang.String, java.lang.String, java.lang.String, com.google.appinventor.components.runtime.util.YailDictionary);
    static boolean shouldShowForegroundNotification();
    static java.lang.String getChannelId();
    static java.lang.String getChannelName();
    static java.lang.String getChannelDescription();
    <fields>;
}

-keepclassmembers class com.hridoy.fcm.FCMService {
    public void onNewToken(java.lang.String);
    public void onMessageReceived(com.google.firebase.messaging.RemoteMessage);
}

# ==============================================================================
# 8. BACKGROUND INLINE RESOURCE & EXTRA SUB-PACKAGE PRESERVATION
# ==============================================================================

# Lock down the structural paths of internal global resources for string lookups.
-keep class com.google.android.gms.common.R { *; }
-keep class com.google.android.gms.common.R$string { *; }
-keep class com.google.firebase.R { *; }
-keep class com.google.firebase.R$string { *; }

# Explicitly isolate the sub-package signatures from repackaging routines
-keep class com.google.firebase.iid.** { *; }