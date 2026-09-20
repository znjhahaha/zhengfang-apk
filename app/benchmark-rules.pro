# Instrumentation reads runtime frame/recomposition metrics in this optimized variant.
-keep class androidx.tracing.** { *; }
-keep class androidx.compose.runtime.Recomposer { public *; }
-keep class androidx.compose.runtime.Recomposer$Companion { public *; }
-keep interface androidx.compose.runtime.RecomposerInfo { *; }
-keep,allowoptimization class kotlin.** { *; }
-keep,allowoptimization class kotlinx.coroutines.** { *; }

# Device tests live in a separate APK and call these public APIs after shrinking.
# Preserve that test boundary; method bodies, private helpers and QuickJS still optimize.
-keep,allowoptimization class com.tyust.course.academic.** { public *; }
# MockWebServer is test-only but shares the app's OkHttp/Okio runtime classes.
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
