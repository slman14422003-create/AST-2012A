package com.ast2012a.clinicalmaster;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * حفظ/تحميل سجل محادثة المساعد الذكي محليًا (ملف JSON داخل تخزين التطبيق
 * الخاص) حتى تفضل المحادثة موجودة لو المستخدم قفل التطبيق ورجع تاني.
 */
public class AiChatStore {

    private static final String FILE_NAME = "ai_chat_history.json";

    public static List<ChatMessage> load(Context ctx) {
        List<ChatMessage> list = new ArrayList<>();
        java.io.File f = new java.io.File(ctx.getFilesDir(), FILE_NAME);
        if (!f.exists()) return list;
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    ctx.openFileInput(FILE_NAME), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();

            JSONArray arr = new JSONArray(sb.toString());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                list.add(new ChatMessage(o.optInt("role", ChatMessage.ROLE_USER), o.optString("text", "")));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return list;
    }

    public static void save(Context ctx, List<ChatMessage> messages) {
        try {
            JSONArray arr = new JSONArray();
            for (ChatMessage m : messages) {
                JSONObject o = new JSONObject();
                o.put("role", m.role);
                o.put("text", m.text);
                arr.put(o);
            }
            FileOutputStream fos = ctx.openFileOutput(FILE_NAME, Context.MODE_PRIVATE);
            fos.write(arr.toString().getBytes(StandardCharsets.UTF_8));
            fos.close();
        } catch (IOException | JSONException e) {
            e.printStackTrace();
        }
    }

    public static void clear(Context ctx) {
        save(ctx, new ArrayList<>());
    }
}
