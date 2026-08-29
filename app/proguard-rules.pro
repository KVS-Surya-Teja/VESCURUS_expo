# VESCURUS ProGuard/R8 keep rules.
# See https://developer.android.com/studio/build/shrink-code for details.

# --- kotlinx.serialization ---
# Companion / synthetic serializer generation must survive shrinking.
-keepattributes InnerClasses
-dontnote kotlinx.serialization.SerializationKt
-keep,includedescriptorclasses class com.example.vescurus.**$$serializer { *; }
-keepclassmembers class com.example.vescurus.** {
    *** Companion;
}
-keepclasseswithmembers class com.example.vescurus.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- Google Gemini SDK (generativeai) ---
-keep class com.google.ai.client.generativeai.** { *; }
-dontwarn com.google.ai.client.generativeai.**

# --- Ktor + CIO ---
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# --- SLF4J (dragged in by Ktor) ---
-dontwarn org.slf4j.**

# --- CameraX ---
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# --- Compose runtime ---
# Compose runtime uses reflection on @Composable functions; the compose
# plugin adds required keep rules automatically, but we suppress warnings.
-dontwarn androidx.compose.**

# --- Debug logger is compiled out in release; suppress any dead references. ---
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}
