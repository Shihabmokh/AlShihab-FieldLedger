# SQLCipher uses JNI; keep its classes intact
-keep class net.zetetic.** { *; }
-keepclassmembers class net.zetetic.** { *; }

# Room entities / generated code
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn net.zetetic.**

# Strip debug logging from release builds
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}
