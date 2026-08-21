plugins {
    id("com.android.application")
}

android {
    namespace = "com.ast2012a.clinicalmaster"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ast2012a.clinicalmaster"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.2.0")
    // مطلوبة لواجهات الإشعارات الحقيقية (NotificationCompat/NotificationManagerCompat)
    // وطلب الأذونات في وقت التشغيل (ActivityResultLauncher) في SettingsActivity
    implementation("androidx.core:core:1.13.1")
    implementation("androidx.activity:activity:1.9.1")
}
