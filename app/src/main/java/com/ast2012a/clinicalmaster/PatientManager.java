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

/**
 * تخزين قائمة المرضى محليًا (ملف JSON داخل تخزين التطبيق الخاص)، بنفس
 * نمط تخزين الحالات المخصصة تمامًا في DataManager.
 */
public class PatientManager {

    private static final String FILE = "patients.json";

    public static List<Patient> loadPatients(Context ctx) {
        List<Patient> list = new ArrayList<>();
        try {
            if (!ctx.getFileStreamPath(FILE).exists()) return list;
            StringBuilder sb = new StringBuilder();
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    ctx.openFileInput(FILE), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();
            JSONArray arr = new JSONArray(sb.toString());
            for (int i = 0; i < arr.length(); i++) list.add(Patient.fromJson(arr.getJSONObject(i)));
        } catch (Exception e) {
            e.printStackTrace();
        }
        return list;
    }

    public static void savePatients(Context ctx, List<Patient> patients) {
        try {
            JSONArray arr = new JSONArray();
            for (Patient p : patients) arr.put(p.toJson());
            FileOutputStream fos = ctx.openFileOutput(FILE, Context.MODE_PRIVATE);
            fos.write(arr.toString(2).getBytes(StandardCharsets.UTF_8));
            fos.close();
        } catch (IOException | JSONException e) {
            e.printStackTrace();
        }
    }

    public static Patient addPatient(Context ctx, Patient p) {
        List<Patient> list = loadPatients(ctx);
        p.id = UUID.randomUUID().toString().substring(0, 12);
        p.createdAt = System.currentTimeMillis();
        list.add(p);
        savePatients(ctx, list);
        return p;
    }

    public static void updatePatient(Context ctx, Patient updated) {
        List<Patient> list = loadPatients(ctx);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(updated.id)) {
                list.set(i, updated);
                break;
            }
        }
        savePatients(ctx, list);
    }

    public static void deletePatient(Context ctx, String id) {
        List<Patient> list = loadPatients(ctx);
        List<Patient> filtered = new ArrayList<>();
        for (Patient p : list) if (!id.equals(p.id)) filtered.add(p);
        savePatients(ctx, filtered);
    }
}
