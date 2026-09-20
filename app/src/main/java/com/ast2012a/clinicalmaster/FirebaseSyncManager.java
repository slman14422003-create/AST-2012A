package com.ast2012a.clinicalmaster;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.firestore.WriteBatch;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * النسخ الاحتياطي السحابي لملفات المرضى عبر Firebase Firestore.
 *
 * التصميم:
 * - مصادقة مجهولة (Anonymous Auth) تلقائية لكل جهاز/تثبيت - بدون شاشة
 *   تسجيل دخول، وبدون كلمة سر. كل تثبيت يحصل على uid ثابت طالما لم يُحذف
 *   التطبيق أو تُمسح بياناته.
 * - كل بيانات المستخدم تُخزَّن تحت users/{uid}/patients/{patientId} فقط،
 *   وقواعد الأمان (انظر firestore.rules المرفق) تمنع أي مستخدم من قراءة
 *   أو كتابة بيانات مستخدم آخر.
 * - "نسخ احتياطي تلقائي": بعد كل حفظ محلي (PatientManager.savePatients)
 *   يُجدوَل رفع مؤجَّل (debounce) بعد توقف التعديلات لثوانٍ قليلة، بدل رفع
 *   فوري مع كل تغيير صغير - يقلّل استهلاك الشبكة والبطارية.
 * - "استعادة من الخادم": تُحمّل كل مستندات المستخدم من Firestore وتستبدل
 *   بها القائمة المحلية بالكامل.
 *
 * ملحوظة مهمة: هذا الكلاس يتوقع وجود app/google-services.json صالح
 * (مسجَّل في Firebase Console بنفس applicationId: com.ast2012a.clinicalmaster).
 * بدونه، أي استدعاء لـ FirebaseAuth/FirebaseFirestore سيرمي
 * IllegalStateException؛ لذلك كل نقطة دخول هنا محاطة بمحاولة/التقاط
 * وتُرجع رسالة عربية واضحة بدل تعطّل التطبيق.
 */
final class FirebaseSyncManager {

    private FirebaseSyncManager() {}

    private static final String COLLECTION_ROOT = "users";
    private static final String COLLECTION_PATIENTS = "patients";

    private static final String PREFS = "cloud_sync_prefs";
    private static final String KEY_AUTO_BACKUP = "auto_backup_enabled";
    private static final String KEY_LAST_BACKUP_AT = "last_backup_at";
    private static final String KEY_LAST_RESTORE_AT = "last_restore_at";

    private static final long AUTO_BACKUP_DEBOUNCE_MS = 4000;
    private static final Handler autoBackupHandler = new Handler(Looper.getMainLooper());
    private static Runnable pendingAutoBackup;

    interface Callback {
        void onSuccess(String message);
        void onError(String message);
    }

    // ----------------------------------------------------------------- إعدادات

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean isAutoBackupEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_AUTO_BACKUP, false);
    }

    static void setAutoBackupEnabled(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(KEY_AUTO_BACKUP, enabled).apply();
    }

    static long getLastBackupAt(Context ctx) {
        return prefs(ctx).getLong(KEY_LAST_BACKUP_AT, 0);
    }

    static long getLastRestoreAt(Context ctx) {
        return prefs(ctx).getLong(KEY_LAST_RESTORE_AT, 0);
    }

    /** هل Firebase مُعدّ فعليًا لهذا البناء (وجود google-services.json صالح)؟ */
    static boolean isConfigured(Context ctx) {
        try {
            return !FirebaseApp.getApps(ctx.getApplicationContext()).isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------- المصادقة

    private static void ensureSignedIn(Context ctx, TaskContinuation<FirebaseUser> onReady, Callback errorSink) {
        if (!isConfigured(ctx)) {
            errorSink.onError("لم يتم إعداد Firebase لهذا التطبيق بعد (ملف google-services.json مفقود).");
            return;
        }
        try {
            FirebaseAuth auth = FirebaseAuth.getInstance();
            FirebaseUser current = auth.getCurrentUser();
            if (current != null) {
                onReady.run(current);
                return;
            }
            auth.signInAnonymously().addOnCompleteListener(task -> {
                if (task.isSuccessful() && auth.getCurrentUser() != null) {
                    onReady.run(auth.getCurrentUser());
                } else {
                    errorSink.onError("تعذّرت المصادقة السحابية: " +
                            (task.getException() != null ? task.getException().getMessage() : "خطأ غير معروف"));
                }
            });
        } catch (Throwable t) {
            errorSink.onError("تعذّر الاتصال بـ Firebase: " + t.getMessage());
        }
    }

    private interface TaskContinuation<T> {
        void run(T value);
    }

    // -------------------------------------------------------- نسخ احتياطي يدوي

    /** يرفع كل المرضى المحليين الآن، ويحذف من السحابة أي مريض لم يعد موجودًا محليًا. */
    static void backupNow(Context ctxIn, Callback callback) {
        Context ctx = ctxIn.getApplicationContext();
        ensureSignedIn(ctx, user -> {
            List<Patient> local = PatientManager.loadPatients(ctx);
            FirebaseFirestore db = FirebaseFirestore.getInstance();
            com.google.firebase.firestore.CollectionReference col = db
                    .collection(COLLECTION_ROOT).document(user.getUid()).collection(COLLECTION_PATIENTS);

            col.get().addOnCompleteListener(snapTask -> {
                Set<String> remoteIds = new HashSet<>();
                if (snapTask.isSuccessful() && snapTask.getResult() != null) {
                    for (DocumentSnapshot d : snapTask.getResult().getDocuments()) remoteIds.add(d.getId());
                }
                Set<String> localIds = new HashSet<>();
                for (Patient p : local) localIds.add(p.id);

                WriteBatch batch = db.batch();
                try {
                    for (Patient p : local) {
                        Map<String, Object> map = JsonUtils.toMap(p.toJson());
                        batch.set(col.document(p.id), map);
                    }
                } catch (Exception e) {
                    callback.onError("تعذّر تجهيز بيانات المرضى للرفع: " + e.getMessage());
                    return;
                }
                for (String remoteId : remoteIds) {
                    if (!localIds.contains(remoteId)) batch.delete(col.document(remoteId));
                }

                batch.commit().addOnCompleteListener(commitTask -> {
                    if (commitTask.isSuccessful()) {
                        prefs(ctx).edit().putLong(KEY_LAST_BACKUP_AT, System.currentTimeMillis()).apply();
                        callback.onSuccess("تم رفع " + local.size() + " ملف مريض إلى السحابة.");
                    } else {
                        callback.onError("فشل رفع النسخة الاحتياطية: " +
                                (commitTask.getException() != null ? commitTask.getException().getMessage() : "خطأ غير معروف"));
                    }
                });
            });
        }, callback);
    }

    // ------------------------------------------------------------------ استعادة

    /** يجلب كل المرضى من السحابة ويستبدل بهم القائمة المحلية بالكامل. */
    static void restoreNow(Context ctxIn, Callback callback) {
        Context ctx = ctxIn.getApplicationContext();
        ensureSignedIn(ctx, user -> {
            FirebaseFirestore db = FirebaseFirestore.getInstance();
            db.collection(COLLECTION_ROOT).document(user.getUid()).collection(COLLECTION_PATIENTS)
                    .get().addOnCompleteListener(task -> {
                        if (!task.isSuccessful() || task.getResult() == null) {
                            callback.onError("تعذّر جلب النسخة السحابية: " +
                                    (task.getException() != null ? task.getException().getMessage() : "خطأ غير معروف"));
                            return;
                        }
                        QuerySnapshot snap = task.getResult();
                        List<Patient> restored = new ArrayList<>();
                        try {
                            for (DocumentSnapshot d : snap.getDocuments()) {
                                Map<String, Object> data = d.getData();
                                if (data == null) continue;
                                JSONObject o = JsonUtils.fromMap(data);
                                restored.add(Patient.fromJson(o));
                            }
                        } catch (Exception e) {
                            callback.onError("تعذّر قراءة بيانات النسخة السحابية: " + e.getMessage());
                            return;
                        }
                        PatientManager.savePatientsSuppressingCloud(ctx, restored);
                        prefs(ctx).edit().putLong(KEY_LAST_RESTORE_AT, System.currentTimeMillis()).apply();
                        callback.onSuccess("تمت استعادة " + restored.size() + " ملف مريض من السحابة.");
                    });
        }, callback);
    }

    // --------------------------------------------------------- نسخ تلقائي مؤجَّل

    /**
     * يُستدعى من PatientManager.savePatients بعد كل حفظ محلي. لا يفعل شيئًا
     * لو "النسخ التلقائي" غير مفعّل من الإعدادات، أو لو Firebase غير مُعدّ.
     * الرفع نفسه مؤجَّل (debounce) حتى لا يحدث استدعاء شبكة مع كل تعديل صغير.
     */
    static void scheduleAutoBackup(Context ctxIn) {
        Context ctx = ctxIn.getApplicationContext();
        if (!isAutoBackupEnabled(ctx) || !isConfigured(ctx)) return;

        if (pendingAutoBackup != null) autoBackupHandler.removeCallbacks(pendingAutoBackup);
        pendingAutoBackup = () -> backupNow(ctx, new Callback() {
            @Override public void onSuccess(String message) { /* صامت: نسخ تلقائي بالخلفية */ }
            @Override public void onError(String message) { /* صامت: سيُعاد المحاولة مع الحفظ التالي */ }
        });
        autoBackupHandler.postDelayed(pendingAutoBackup, AUTO_BACKUP_DEBOUNCE_MS);
    }
}
