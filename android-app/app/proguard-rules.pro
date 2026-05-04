# =====================================================================
# ProGuard / R8 rules for Santhalia Rate Card.
#
# We turn ON minify + resource shrinking for release builds. The rules
# below preserve everything that reflective frameworks (Room, Moshi,
# Retrofit) need at runtime.
# =====================================================================

# Keep line numbers for crash debugging in case a user reports an issue.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Compose: keep Composable signatures (Compose compiler already handles
# the heavy lifting; this is just defensive).
-dontwarn androidx.compose.**

# ---------------------------------------------------------------------
# Room
# ---------------------------------------------------------------------
# Room generates implementation classes via KSP. The generated DAO
# classes already have the right keep rules baked in, but we keep all
# entities and the database class for safety because some reflection
# happens around schema verification.
-keep class in.santhaliastore.ratecard.data.db.entity.** { *; }
-keep class in.santhaliastore.ratecard.data.db.AppDatabase { *; }
-keep class in.santhaliastore.ratecard.data.db.AppDatabase_Impl { *; }
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Database class * { *; }
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**

# ---------------------------------------------------------------------
# Moshi (reflection-based adapter)
# ---------------------------------------------------------------------
-keepclassmembers class * {
    @com.squareup.moshi.FromJson <methods>;
    @com.squareup.moshi.ToJson <methods>;
}
-keep class com.squareup.moshi.** { *; }
-keep interface com.squareup.moshi.** { *; }
-keep class kotlin.Metadata { *; }
-keep class kotlin.reflect.** { *; }
-keepclassmembers,allowobfuscation class * {
    @com.squareup.moshi.Json <fields>;
}

# Keep our DTO classes — Moshi reflects on field names.
-keep class in.santhaliastore.ratecard.sync.** { *; }
-keepclassmembers class in.santhaliastore.ratecard.sync.** { *; }

# ---------------------------------------------------------------------
# Retrofit / OkHttp
# ---------------------------------------------------------------------
-dontwarn retrofit2.**
-dontwarn okhttp3.**
-dontwarn okio.**
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response

# Retrofit reads generic types via reflection.
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault

-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# Our Retrofit interface(s) — keep methods and annotations.
-keep interface in.santhaliastore.ratecard.sync.AppsScriptApi { *; }

# ---------------------------------------------------------------------
# Kotlin coroutines
# ---------------------------------------------------------------------
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# ---------------------------------------------------------------------
# WorkManager
# ---------------------------------------------------------------------
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.CoroutineWorker
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
