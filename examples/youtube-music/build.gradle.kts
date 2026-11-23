plugins {
    id("com.android.application")
}

// Same tag-derived version as the library (see the root build script).
val soniqVersion: String = rootProject.extra["soniqVersion"] as String

/** `1.2.3` → 10203, so every release has a higher versionCode than the last. */
fun versionCodeOf(version: String): Int {
    val parts = version.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 } + listOf(0, 0, 0)
    return (parts[0] * 10000 + parts[1] * 100 + parts[2]).coerceAtLeast(1)
}

android {
    namespace = "com.soniq.example.youtubemusic"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.soniq.example.youtubemusic"
        minSdk = 26
        targetSdk = 35
        versionCode = versionCodeOf(soniqVersion)
        versionName = soniqVersion
    }

    buildTypes {
        release {
            // The example is a starting point, not a store release: sign with
            // the debug key so `assembleRelease` works out of the box.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
    }
}

dependencies {
    // In a separate repository replace this with the published artifact, e.g.
    //   implementation("com.soniq.base:soniq-base:0.1.0")      (GitHub Packages)
    //   implementation("com.github.mob5824m-wq:Soniq-Base:v0.1.0") (JitPack)
    // or include the library sources as a Git submodule / composite build.
    implementation(project(":soniq-base"))
}
