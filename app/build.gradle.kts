plugins {
    id("com.android.application")
    id("com.google.gms.google-services")
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
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.2.0")
    // مطلوبة لواجهات الإشعارات الحقيقية (NotificationCompat/NotificationManagerCompat)
    // وطلب الأذونات في وقت التشغيل (ActivityResultLauncher) في SettingsActivity
    implementation("androidx.core:core:1.13.1")
    implementation("androidx.activity:activity:1.9.1")

    // النسخ الاحتياطي السحابي لملفات المرضى (Firebase Firestore + مصادقة
    // مجهولة تلقائية لكل جهاز، بدون شاشة تسجيل دخول). الإصدارات تُدار عبر
    // Firebase BOM حتى تبقى متوافقة مع بعضها دائمًا.
    implementation(platform("com.google.firebase:firebase-bom:33.5.1"))
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-analytics")
}
