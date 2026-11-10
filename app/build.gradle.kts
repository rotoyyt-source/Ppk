plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.sayad.orders.pro"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sayad.orders.pro"
        minSdk = 26
        targetSdk = 34
        versionCode = 300 + (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 3)
        versionName = "2.1"
    }

    // توقيع ثابت: نفس المفتاح بكل بناء، عشان النسخ الجاية تتثبّت "تحديث" فوق القديمة
    // بدون ما يحتاج المستخدم يمسح التطبيق أول. لا تحذف ملف sniper-release.keystore
    // ولا تغيّر كلمة السر هون أبداً، وإلا بترجع نفس المشكلة.
    signingConfigs {
        create("persistent") {
            storeFile = file("sniper-release.keystore")
            storePassword = "Sniper2026Orders!"
            keyAlias = "sniperorderspro"
            keyPassword = "Sniper2026Orders!"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("persistent")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("persistent")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    implementation(platform("com.google.firebase:firebase-bom:33.5.1"))
    implementation("com.google.firebase:firebase-firestore")
}
