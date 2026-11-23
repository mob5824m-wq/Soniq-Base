// Soniq Base — top-level Gradle build.
//
// :soniq-base is an Android library with zero runtime dependencies: it is built
// against the Android framework only (minSdk 26 / targetSdk 35). The example
// app depends on it and adds a music source.
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
}
