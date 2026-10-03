# ===== قواعد R8 لتطبيق AST-2012A =====

# أرقام الأسطر في تقارير الأعطال (يُرفع ملف mapping.txt مع كل إصدار)
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod
-renamesourcefileattribute SourceFile

# Tesseract OCR: مكتبة أصلية (JNI) تستدعي دوال Java بأسمائها
-keep class com.googlecode.tesseract.android.** { *; }
-keepclasseswithmembernames class * { native <methods>; }

# PDFBox-Android: يحمّل الخطوط والفلاتر والموارد بالاسم ديناميكيًا
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.harmony.** { *; }
-dontwarn com.tom_roush.pdfbox.**
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**
-dontwarn org.spongycastle.**
-dontwarn javax.xml.**
-dontwarn java.awt.**
-dontwarn javax.imageio.**

# OkHttp (قراءة صوتية عصبية عبر WebSocket) - كلاسات المنصات الاختيارية
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# Firebase/Firestore: قواعدها تأتي مع المكتبات نفسها (consumer rules)

# sherpa-onnx (الصوت العصبي المحلي): مكتبة JNI تقرأ حقول كلاسات الإعداد وتبني النتائج بأسمائها،
# فأي إعادة تسمية من R8 تكسر التشغيل (UnsatisfiedLinkError / NoSuchFieldError)
-keep class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**

# commons-compress: كلاسات اختيارية (ضغط/تشفير) غير مستخدمة هنا
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.xz.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
