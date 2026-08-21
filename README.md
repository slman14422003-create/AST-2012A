# AST-2012A Clinical Master — تطبيق أندرويد أصلي بلغة Java

إعادة بناء كاملة للتطبيق بلغة **Java** باستخدام أدوات أندرويد القياسية
(Android SDK + Gradle) — بالضبط نفس الطريقة اللي بتُبنى بيها أي تطبيق
أندرويد احترافي عادي. **لا يوجد WebView ولا TWA ولا Kivy/Python/Buildozer
إطلاقًا** — تطبيق مستقل 100% من أول لحظة تثبيت.

## ليه Java بدل بايثون؟

أدوات بايثون-لأندرويد (Kivy/Buildozer/python-for-android) بتحتاج تجميع
CPython بالكامل من المصدر لكل بناء، وده بطيء جدًا (30-90+ دقيقة) وهش
(تعارضات إصدارات متكررة زي اللي واجهناها). أدوات Java/Gradle القياسية
مُحسّنة ومُختبرة من ملايين المطورين حول العالم، وبتبني في **دقائق معدودة**
بثبات عالي.

## المحتوى

- ✅ نفس الـ 120 حالة سريرية + موسوعة الأنماط، منقولة بالكامل بدون فقدان أي بيانات
- ✅ نفس خوارزمية البحث بالضبط (المرادفات، تصحيح الأخطاء الإملائية، الترتيب حسب درجة التطابق) - منقولة سطرًا بسطر من المنطق الأصلي
- ✅ زر ➕ يفتح شاشة كاملة مخصصة (`AddEditCaseActivity`) لإضافة حالة جديدة
- ✅ تعديل الحالات المخصصة من شاشة "حالاتي" أو من تفاصيل أي حالة
- ✅ إشعارات أندرويد حقيقية عبر `NotificationManagerCompat` (مش محاكاة)
- ✅ يعمل بالكامل بدون إنترنت - كل البيانات والبحث محليين على الجهاز

## هيكل المشروع

```
app/src/main/
  AndroidManifest.xml
  java/com/ast2012a/clinicalmaster/
    MainActivity.java          الشاشة الرئيسية (بحث + قائمة نتائج + زر +)
    CaseDetailActivity.java    تفاصيل حالة معينة
    AddEditCaseActivity.java   شاشة إضافة/تعديل حالة كاملة
    MyCasesActivity.java       إدارة الحالات المخصصة
    EncyclopediaActivity.java  موسوعة أنماط الجهاز
    SettingsActivity.java      الإشعارات + التصدير + المسح
    DataManager.java           تحميل البيانات + محرك البحث + CRUD
    CaseItem.java               نموذج بيانات الحالة
    CaseAdapter.java / CaseRowAdapter.java   محولات RecyclerView
  res/layout/                  كل تصاميم الشاشات (XML قياسي)
  res/values/                  الألوان والنصوص والثيم
  assets/
    clinical_database.json     قاعدة الحالات الـ 120
    modes_encyclopedia.json    موسوعة الأنماط
app/build.gradle.kts           إعدادات بناء التطبيق
build.gradle.kts               إعدادات المشروع (جذر)
settings.gradle.kts
.github/workflows/build-apk.yml   بناء APK تلقائي + إصدار Release
```

## البناء إلى APK

### تلقائيًا عبر GitHub (لا حاجة لأي إعداد يدوي)
ادفع (`push`) المشروع بالكامل إلى `main`، ووركفلو `build-apk.yml` هيبني
الـ APK باستخدام Gradle القياسي (**دقائق معدودة، مش ساعات**) وينشره في
تبويب **Releases** تلقائيًا.

### محليًا (اختياري، لو عندك Android Studio)
افتح المجلد كمشروع في Android Studio مباشرة، أو من الطرفية:
```bash
gradle :app:assembleDebug
```
الملف الناتج هيكون في `app/build/outputs/apk/debug/`.

## ملاحظة عن التوقيع (Signing)

نسخة الـ APK الحالية موقّعة بمفتاح تصحيح (debug key) تلقائي من Android
SDK نفسه - كافي تمامًا للتثبيت المباشر والتجربة. لنشر التطبيق على متجر
Google Play لاحقًا، ستحتاج مفتاح إصدار (release key) ثابت خاص بيك -
راجع توثيق Android الرسمي حول "Sign your app" لإعداده.
