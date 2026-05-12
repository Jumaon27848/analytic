# AppsFlyer SDK
-keep class com.appsflyer.** { *; }
-dontwarn com.appsflyer.**

# Google Play Install Referrer (used by AppsFlyer for attribution)
-keep public class com.android.installreferrer.** { *; }
-dontwarn com.android.installreferrer.**

# Google Play Services Ads Identifier (GAID lookup used by AppsFlyer + this lib)
-keep class com.google.android.gms.ads.identifier.** { *; }
-dontwarn com.google.android.gms.ads.identifier.**

# Microsoft Clarity SDK (session replay) — uses reflection / protobuf internals
-keep class com.microsoft.clarity.** { *; }
-dontwarn com.microsoft.clarity.**

# protobuf-javalite (transitive Clarity dep, reflection-heavy)
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**
