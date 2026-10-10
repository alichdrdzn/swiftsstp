import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.swiftsstp.client"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.swiftsstp.client"
        minSdk = 26
        targetSdk = 35
        // CI overrides both from the git tag (see .github/workflows/release.yml).
        versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "1.0.0"

        ndk {
            // Ship only the ARM ABIs. Every device this client runs on is ARM;
            // the x86 / x86_64 copies of libgojni.so exist for emulators only
            // and cost ~13.7 MB of APK that no user can benefit from.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        getByName("debug") {
            // Shrink the debug build as well: the gomobile binding drags in a
            // lot of otherwise-dead code, and shipping an unminified debug APK
            // hides what the release actually looks like. Tradeoff: debug
            // builds are slower (R8 + resource shrinking run on every one).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")

            // Signing: prefer a real release keystore supplied via CI
            // secrets (SIGNING_KEYSTORE_BASE64 / SIGNING_KEYSTORE_PASSWORD /
            // SIGNING_KEY_ALIAS / SIGNING_KEY_PASSWORD) so GitHub-released
            // APKs are upgradeable in place. Locally (or when the secrets
            // are absent) fall back to the debug keystore so the APK still
            // installs straight from a build.
            val releaseKeystoreB64 = System.getenv("SIGNING_KEYSTORE_BASE64")
            signingConfig = if (!releaseKeystoreB64.isNullOrBlank()) {
                signingConfigs.create("release") {
                    val decoded = Base64.getDecoder().decode(releaseKeystoreB64)
                    val ksFile = File(project.layout.buildDirectory.get().asFile, "release.keystore")
                    // This runs at Gradle's CONFIGURATION phase, before any
                    // task creates android/app/build/ — on a fresh checkout
                    // the parent dir does not exist yet, so writeBytes fails.
                    ksFile.parentFile.mkdirs()
                    ksFile.writeBytes(decoded)
                    storeFile = ksFile
                    storePassword = System.getenv("SIGNING_KEYSTORE_PASSWORD")
                    keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                    keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
                }
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // gomobile binding: the SwiftSSTP SSTP engine (swiftsstp.mobile.*).
    implementation(files("libs/mobile.aar"))

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // Icons: only the small core set (ArrowBack, ArrowDropDown, Home, Person,
    // …). The extended icon artifact shipped ~10k unused glyphs and was the
    // single biggest dex consumer (~40 MB raw / ~12 MB in the APK); the few
    // glyphs it was borrowed for now live in ui/AppIcons.kt.
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
