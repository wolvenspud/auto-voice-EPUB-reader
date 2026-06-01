# Room — keep entity and DAO classes
-keep class * extends androidx.room.RoomDatabase
-keepclassmembers class ** {
    @androidx.room.* <methods>;
    @androidx.room.* <fields>;
}

# Hilt
-keep class dagger.hilt.** { *; }
-keep @dagger.hilt.android.HiltAndroidApp class * { *; }
-keep @dagger.hilt.android.AndroidEntryPoint class * { *; }

# Media3 / ExoPlayer
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Jsoup
-keep public class org.jsoup.** { *; }

# Kotlin serialization (future-proofing)
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
