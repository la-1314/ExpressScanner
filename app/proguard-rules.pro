# Keep ML Kit & CameraX related classes
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.** { *; }
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}
