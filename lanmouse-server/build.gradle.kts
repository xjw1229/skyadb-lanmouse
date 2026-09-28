plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.server.skyadb.lanmouse"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.server.skyadb.lanmouse"
        minSdk = 21
        targetSdk = 34
        versionCode = 1036
        versionName = "1.0.36"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.okhttp)
}
