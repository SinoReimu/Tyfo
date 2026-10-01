plugins {
    id("com.android.application")
}

android {
    namespace = "com.shiro.tyfo.test.flagsecure"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.shiro.tyfo.test.flagsecure"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
    }
}
