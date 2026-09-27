package com.ast2012a.clinicalmaster;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** تخزين برامج العلاج الفيزيائي محليًا، بنفس نمط تخزين الحالات المخصصة. */
public class TreatmentProgramManager {

    private static final String FILE = "treatment_programs.json";

    public static List<TreatmentProgram> loadPrograms(Context ctx) {
        List<TreatmentProgram> list = new ArrayList<>();
        try {
            if (!ctx.getFileStreamPath(FILE).exists()) return list;
            StringBuilder sb = new StringBuilder();
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    ctx.openFileInput(FILE), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();
            JSONArray arr = new JSONArray(sb.toString());
            for (int i = 0; i < arr.length(); i++) list.add(TreatmentProgram.fromJson(arr.getJSONObject(i)));
        } catch (Exception e) {
            e.printStackTrace();
        }
        return list;
    }

    public static void savePrograms(Context ctx, List<TreatmentProgram> programs) {
        try {
            JSONArray arr = new JSONArray();
            for (TreatmentProgram t : programs) arr.put(t.toJson());
            FileOutputStream fos = ctx.openFileOutput(FILE, Context.MODE_PRIVATE);
            fos.write(arr.toString(2).getBytes(StandardCharsets.UTF_8));
            fos.close();
        } catch (IOException | JSONException e) {
            e.printStackTrace();
        }
    }

    public static TreatmentProgram addProgram(Context ctx, TreatmentProgram t) {
        List<TreatmentProgram> list = loadPrograms(ctx);
        t.id = UUID.randomUUID().toString().substring(0, 12);
        t.createdAt = System.currentTimeMillis();
        list.add(t);
        savePrograms(ctx, list);
        return t;
    }

    public static void updateProgram(Context ctx, TreatmentProgram updated) {
        List<TreatmentProgram> list = loadPrograms(ctx);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(updated.id)) {
                list.set(i, updated);
                break;
            }
        }
        savePrograms(ctx, list);
    }

    public static void deleteProgram(Context ctx, String id) {
        List<TreatmentProgram> list = loadPrograms(ctx);
        List<TreatmentProgram> filtered = new ArrayList<>();
        for (TreatmentProgram t : list) if (!id.equals(t.id)) filtered.add(t);
        savePrograms(ctx, filtered);
    }

    /** حد أقصى لعدد البرامج المُرفَقة في النظرة العامة، بنفس فكرة
     *  PatientManager.MAX_PATIENTS_IN_OVERVIEW - تجنبًا لتضخيم الطلب لو
     *  المستخدم عنده عدد كبير من البرامج المحفوظة. */
    private static final int MAX_PROGRAMS_IN_OVERVIEW = 15;

    /**
     * نظرة عامة على كل برامج العلاج اللي بناها المستخدم بنفسه - تُستخدم من
     * AiOrchestrator لما سؤال المستخدم يتكلم عن "برامجه" أو "برامج العلاج"
     * بشكل عام (مش عن حالة/بروتوكول جهاز واحد). بعكس ملف المريض، برامج
     * العلاج مالهاش بيانات تعريفية شخصية أصلًا (تشخيص وخطة علاج فقط) فمفيش
     * داعي لأي إخفاء هوية هنا. يرجع null لو مفيش أي برنامج محفوظ أصلًا.
     */
    public static String buildProgramsOverviewContext(Context ctx) {
        List<TreatmentProgram> all = loadPrograms(ctx);
        if (all.isEmpty()) return null;

        StringBuilder sb = new StringBuilder();
        sb.append("نظرة عامة على برامج العلاج المتكاملة التي بناها المستخدم بنفسه (من شاشة \"برامج العلاج\" ")
          .append("داخل التطبيق - كل برنامج أوسع من حالة/بروتوكول جهاز واحد، وقد يكون مرتبطًا بأكثر من مريض):\n");

        int shown = Math.min(MAX_PROGRAMS_IN_OVERVIEW, all.size());
        for (int i = 0; i < shown; i++) {
            TreatmentProgram t = all.get(i);
            sb.append("• ").append(t.title.trim().isEmpty() ? "(بدون عنوان)" : t.title.trim());
            List<String> parts = new ArrayList<>();
            if (!t.diagnosis.trim().isEmpty()) parts.add("التشخيص: " + t.diagnosis.trim());
            if (!t.goals.trim().isEmpty()) parts.add("الأهداف: " + t.goals.trim());
            if (!t.phases.trim().isEmpty()) parts.add("المراحل: " + t.phases.trim());
            if (!t.precautions.trim().isEmpty()) parts.add("الاحتياطات: " + t.precautions.trim());
            if (!parts.isEmpty()) {
                // StringBuilder بدل String.join(CharSequence, Iterable) عمدًا:
                // minSdk الحالي 24 والدالة دي متاحة من API 26 بس (نفس القيد
                // الموثّق في تعليق AiOrchestrator.buildFormulationContext).
                StringBuilder joined = new StringBuilder();
                for (int j = 0; j < parts.size(); j++) {
                    if (j > 0) joined.append(" | ");
                    joined.append(parts.get(j));
                }
                sb.append(" — ").append(joined);
            }
            sb.append("\n");
        }
        if (all.size() > shown) {
            sb.append("(يوجد ").append(all.size() - shown).append(" برنامج إضافي لم يُعرض هنا لتقليل الحجم.)\n");
        }
        return sb.toString().trim();
    }
}
