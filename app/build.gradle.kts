plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ro.vik.slwoff"
    compileSdk = 34

    defaultConfig {
        applicationId = "ro.vik.slwoff"
        minSdk = 28
        // Ca OverDrive: targetSdk vechi = fără restricții de background pentru boot/alarme.
        targetSdk = 25
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false // SlwDaemon e pornit prin app_process după nume — nu obfusca
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

dependencies {
    implementation("dev.mobile:dadb:2.0.0")
    // Clasele BYD există deja pe unitate; stub-urile servesc doar la compilare.
    compileOnly(project(":bydstubs"))
}
