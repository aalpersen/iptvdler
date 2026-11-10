# iptvdler release (R8) rules. Most libraries (Compose, Koin, Ktor, OkHttp, Media3, SQLDelight,
# kotlinx.serialization) ship their own consumer rules; these cover what they don't.

# Ktor / OkHttp optional dependencies that aren't on Android.
-dontwarn org.slf4j.**
-dontwarn java.lang.management.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Navigation routes are @Serializable classes looked up by their serializer.
-keepclassmembers @kotlinx.serialization.Serializable class io.github.aalpersen.iptvdler.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep line numbers for readable crash logs.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
