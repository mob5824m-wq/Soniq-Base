/**
 * Library version. Bump it here for a release; CI overrides it with the tag
 * name when publishing (`-PsoniqVersion=1.2.3` for `v1.2.3`).
 */
val soniqVersion: String = (findProperty("soniqVersion") as String?) ?: "0.1.0"

plugins {
    id("com.android.library")
    `maven-publish`
}

// Maven coordinates, so `includeBuild()` substitutions and `publishToMavenLocal`
// both resolve to com.soniq.base:soniq-base:<version>.
group = "com.soniq.base"
version = soniqVersion

android {
    namespace = "com.soniq.base"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "consumer-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    // Pure-JVM unit tests for queue, lyrics, position and model logic.
    testImplementation("junit:junit:4.13.2")
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.soniq.base"
            artifactId = "soniq-base"
            version = soniqVersion

            afterEvaluate {
                from(components["release"])
            }

            pom {
                name.set("Soniq Base")
                description.set("A zero-dependency Android framework for music apps: playback, queue, library, downloads, lyrics and ListenBrainz scrobbling behind a MusicProvider SPI.")
                url.set("https://github.com/mob5824m-wq/Soniq-Base")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://github.com/mob5824m-wq/Soniq-Base/blob/main/LICENSE")
                    }
                }
                developers {
                    developer {
                        id.set("mob5824m-wq")
                        name.set("mob5824m-wq")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/mob5824m-wq/Soniq-Base.git")
                    developerConnection.set("scm:git:ssh://github.com/mob5824m-wq/Soniq-Base.git")
                    url.set("https://github.com/mob5824m-wq/Soniq-Base")
                }
            }
        }
    }

    repositories {
        // Publish with: ./gradlew :soniq-base:publish -Pgpr.user=<user> -Pgpr.key=<token>
        // (or GITHUB_ACTOR / GITHUB_TOKEN in the environment, as CI does).
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/mob5824m-wq/Soniq-Base")
            credentials {
                // Locally: -Pgpr.user=<github user> -Pgpr.key=<PAT with write:packages>
                // In CI: the automatic GITHUB_ACTOR / GITHUB_TOKEN.
                username = (findProperty("gpr.user") as String?) ?: System.getenv("GITHUB_ACTOR")
                password = (findProperty("gpr.key") as String?) ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
