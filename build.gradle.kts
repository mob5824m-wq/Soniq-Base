// Soniq Music — top-level Gradle build.
//
// The app intentionally has zero runtime dependencies: it is built against the
// Android framework only (minSdk 26 / targetSdk 35). That keeps the build
// reproducible in offline CI and removes the AndroidX / Jetpack resolution
// surface that the original Kobo Arc 7 build could not provide.
plugins {
    id("com.android.application") version "8.7.3" apply false
}
