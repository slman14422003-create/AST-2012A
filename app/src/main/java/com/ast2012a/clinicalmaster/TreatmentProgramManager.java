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
}
