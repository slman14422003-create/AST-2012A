plugins {
    id("com.android.application")
}

// ملف google-services.json (إعداد Firebase) يجب أن يكون في مجلد app/.
// بدونه كان بناء المهمة processDebugGoogleServices يفشل. الآن يُطبَّق البلجن فقط
// عند وجود الملف، وإلا يُبنى التطبيق بشكل طبيعي وتظهر رسالة "Firebase غير مُعدّ"
// داخل التطبيق بدل فشل البناء (FirebaseSyncManager يتعامل مع هذه الحالة).
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
} else {
    logger.warn("google-services.json غير موجود في app/ - تم تخطي بلجن Google Services (النسخ السحابي معطّل).")
}

// أرقام الإصدار تأتي من GitHub Actions عند وجودها، وإلا قيم افتراضية للبناء المحلي
val ciRunNumber: Int? = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
val ciVersionName: String? = System.getenv("APP_VERSION_NAME")?.takeIf { it.isNotBlank() }

android {
    namespace = "com.ast2012a.clinicalmaster"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ast2012a.clinicalmaster"
        minSdk = 24
        // targetSdk تُركت على 34 عمدًا: رفعها إلى 35+ يفرض وضع Edge-to-Edge على كل الشاشات
        // ويغيّر شكل التصميم الحالي. compileSdk = 36 يعطيك أحدث المكتبات بدون هذا التأثير.
        targetSdk = 34
        versionCode = ciRunNumber ?: 1
        versionName = ciVersionName ?: (if (ciRunNumber != null) "1.0.$ciRunNumber" else "1.0")
    }

    // توقيع نسخة Release: يُفعَّل فقط لو مفاتيح التوقيع موجودة (أسرار GitHub)
    val keystorePath: String? = System.getenv("KEYSTORE_FILE")
    signingConfigs {
        if (!keystorePath.isNullOrBlank() && file(keystorePath).exists()) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // الـ Lint يعمل ويُنتج تقريرًا لكن لا يُفشل البناء
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.2.0")
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.3.0")
    // مطلوبة لواجهات الإشعارات الحقيقية (NotificationCompat/NotificationManagerCompat)
    // وطلب الأذونات في وقت التشغيل (ActivityResultLauncher) في SettingsActivity
    implementation("androidx.core:core:1.19.0")
    implementation("androidx.activity:activity:1.13.0")

    // النسخ الاحتياطي السحابي لملفات المرضى (Firebase Firestore + مصادقة
    // مجهولة تلقائية لكل جهاز، بدون شاشة تسجيل دخول). الإصدارات تُدار عبر
    // Firebase BOM حتى تبقى متوافقة مع بعضها دائمًا.
    implementation(platform("com.google.firebase:firebase-bom:34.18.0"))
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-analytics")
}
