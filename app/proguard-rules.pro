# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# ============ General ============
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes Exceptions

# Keep class names for debugging
-renamesourcefileattribute SourceFile

# ============ Kotlin ============
-dontwarn kotlin.**
-keep class kotlin.** { *; }
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlin.Metadata {
    public <methods>;
}

# ============ Jetpack Compose ============
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# Keep Composable functions
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}

# ============ OkHttp ============
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keepclassmembers class okhttp3.** { *; }

# OkHttp platform checks
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ============ Jsoup ============
-keep class org.jsoup.** { *; }
-keepclassmembers class org.jsoup.** { *; }
-dontwarn org.jsoup.**

# ============ Coroutines ============
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**

# ============ Data Classes ============
# Keep data classes used for parsing JSON or serialization
-keep class com.tyust.course.model.** { *; }
-keep class com.tyust.course.manager.ScheduleSettingsManager$PeriodTime { *; }
-keep class com.tyust.course.manager.ScheduleSettingsManager$CustomCourse { *; }

# ============ App Classes ============
# Keep all public classes in the app package
-keep public class com.tyust.course.** { public *; }

# ============ Fragment Navigation ============
-keepnames class * extends androidx.fragment.app.Fragment

# ============ R8 Specific ============
-allowaccessmodification
-repackageclasses ''

# ============ Remove Logging in Release ============
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
