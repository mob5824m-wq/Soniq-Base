plugins {
    id("com.android.application")
}

android {
    namespace = "com.soniq.music"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.soniq.music"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // The upstream Kobo project used a permanent release signer. This
            // port has no release keystore checked in; developers sign locally
            // with `./gradlew assembleRelease` after adding their own config.
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

    packaging {
        resources {
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }
}

dependencies {
    // Pure-JVM unit tests for queue, lyric and model logic.
    testImplementation("junit:junit:4.13.2")
}
