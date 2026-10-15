import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The release key lives outside the project (never in git): ~/.marcarpro/keystore.properties.
// Every update must be signed with it, or panels refuse to install it: keep a backup.
val releaseKey = File(System.getProperty("user.home"), ".marcarpro/keystore.properties")
    .takeIf { it.exists() }
    ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.maestroseb.marcarpro"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.maestroseb.marcarpro"
        minSdk = 26
        targetSdk = 36
        versionCode = 18
        versionName = "1.4.0"
        // Where updates come from: the GitHub repository whose latest release (tag v<versionName>)
        // carries the signed APK. Empty = no updates.
        buildConfigField("String", "UPDATE_REPO", "\"maestroseb/marcarpro\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    androidResources {
        // Only our two languages: drops the libraries' translations.
        localeFilters += listOf("en", "es")
    }

    signingConfigs {
        if (releaseKey != null) create("release") {
            storeFile = file(releaseKey.getProperty("storeFile"))
            storePassword = releaseKey.getProperty("storePassword")
            keyAlias = releaseKey.getProperty("keyAlias")
            keyPassword = releaseKey.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            // The panels run the release build (faster: R8, no debug overhead), signed with the
            // project's own key (the debug key only on a machine without it).
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
    }
    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-ktx:1.11.0")
    implementation("androidx.dynamicanimation:dynamicanimation:1.1.0")
    implementation("androidx.input:input-motionprediction:1.0.0")
    // Predicts where the pen is going, to draw ink a frame ahead of the digitizer.

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("junit:junit:4.13.2")
}
