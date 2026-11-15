plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ai.movens.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "ai.movens.app"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            // 공모전 제출용 release APK: 별도 출시 키가 없으므로 디버그 키로 서명해 바로 설치·실행되게 한다.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // Standard AAOS Car API (speed, gear) — provided by the platform at runtime.
    useLibrary("android.car")
}

dependencies {
    // Docs say 2.3.1, but the Pleos Maven repo only publishes 2.0.3 and 2.3.2.
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    testImplementation("junit:junit:4.13.2")
}
