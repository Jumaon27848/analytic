# Google Play Install Referrer
-keep public class com.android.installreferrer.** { *; }
-dontwarn com.android.installreferrer.**

# Google Play Services Ads Identifier (GAID lookup)
-keep class com.google.android.gms.ads.identifier.** { *; }
-dontwarn com.google.android.gms.ads.identifier.**

# Microsoft Clarity SDK (session replay) — uses reflection / protobuf internals
-keep class com.microsoft.clarity.** { *; }
-dontwarn com.microsoft.clarity.**

# protobuf-javalite (transitive Clarity dep, reflection-heavy)
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**
