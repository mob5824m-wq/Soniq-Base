# R8 / resource-shrinker rules for the example app.
#
# The framework is reflection-free, so it needs no keep rules of its own; these
# are the belt-and-braces rules a minified release build wants anyway. They only
# keep names -- shrinking still happens -- so the APK stays small while stack
# traces, manifest entries and any future reflection keep working.

# Entry points referenced by name from the manifest.
-keep class com.soniq.example.youtubemusic.ExampleApp { *; }
-keep class com.soniq.example.youtubemusic.MainActivity { *; }

# Readable stack traces for the framework, which is compiled here as source.
-keepnames class com.soniq.base.** { *; }

# Enum valueOf()/values() and Parcelable CREATOR are looked up reflectively.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
