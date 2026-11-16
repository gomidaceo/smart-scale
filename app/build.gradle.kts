plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// google-services.json 이 있을 때만 Firebase 설정 적용
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "kr.smartscale.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "kr.smartscale.app"
        minSdk = 26
        targetSdk = 34
        versionCode = runNumber
        versionName = "1.0.$runNumber"
    }

    signingConfigs {
        create("rel") {
            storeFile = file("release.jks")
            storePassword = "smartscale"
            keyAlias = "scale"
            keyPassword = "smartscale"
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("rel")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("rel")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation(platform("com.google.firebase:firebase-bom:33.1.2"))
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-auth")
}
