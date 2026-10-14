# Proguard / R8 configuration for Tactile

# Keep OkHttp internals
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Keep Moshi models
-keepclassmembers class * {
    @com.squareup.moshi.Json *;
}
-keep class com.squareup.moshi.** { *; }

# Keep data models used in JSON parsing
-keep class com.example.tv.** { *; }
-keep class com.example.model.** { *; }
