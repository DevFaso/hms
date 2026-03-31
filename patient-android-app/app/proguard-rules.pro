# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in the Android SDK tools/proguard/proguard-android.txt

# Keep Hilt generated classes
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-dontwarn dagger.hilt.**

# Keep app classes needed by Hilt/Compose codegen
-keep class com.bitnesttechs.hms.patient.ComposableSingletons** { *; }
-keep class com.bitnesttechs.hms.patient.core.auth.** { *; }
-keep class com.bitnesttechs.hms.patient.core.locale.** { *; }

# Keep Moshi JSON adapters
-keep class com.squareup.moshi.** { *; }
-keep @com.squareup.moshi.JsonClass class * { *; }
-keepclassmembers class * {
    @com.squareup.moshi.FromJson *;
    @com.squareup.moshi.ToJson *;
}

# Keep Retrofit interfaces
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# Keep OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# Keep model data classes
-keep class com.bitnesttechs.hms.patient.core.models.** { *; }

# Keep navigation args
-keepnames class * implements android.os.Parcelable
-keepclassmembers class * implements android.os.Parcelable {
    static ** CREATOR;
}

# Keep Coroutines
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# Keep Material Icons Extended (prevents R8 from stripping icon vector paths)
-keep class androidx.compose.material.icons.** { *; }

# Remove logging in release
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}
