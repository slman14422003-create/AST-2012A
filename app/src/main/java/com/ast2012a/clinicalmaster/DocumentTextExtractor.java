package com.ast2012a.clinicalmaster;

import android.content.Context;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * استخراج نص عادي من ملف محلي (نسخة مُنزَّلة من التخزين السحابي عبر
 * CloudStorageClient.downloadToFile) بناءً على امتداده - يُستخدم من
 * CloudKnowledgeManager عشان "المساعد الذكي" يقدر فعليًا يقرأ محتوى
 * PDF/مستندات المستخدم بدل ما يتعامل معاها كملفات مغلقة.
 *
 * مدعوم حاليًا: PDF (عبر PdfBox-Android، استخراج نص حقيقي مش مجرد رسم
 * الصفحة كصورة زي PdfViewerActivity)، وTXT/MD (قراءة مباشرة). أي امتداد
 * تاني (docx مثلًا) بيرجع null حاليًا بدل محاولة استخراج غير موثوقة.
 *
 * أي خطأ أثناء الاستخراج (ملف تالف، صفحات ممسوحة ضوئيًا بدون طبقة نص،
 * الخ) بيتم ابتلاعه ويترجع null - عشان فشل استخراج ملف واحد ما يوقفش
 * باقي مسار التأريض السحابي كله.
 */
final class DocumentTextExtractor {

    private DocumentTextExtractor() {}

    /** حد أقصى لطول النص المستخرج من أي ملف - يكفي لأي بروتوكول/مقال
     *  علاجي عادي، ويمنع ملف ضخم واحد من التهام كل ميزانية التأريض. */
    private static final int MAX_EXTRACTED_CHARS = 20000;

    private static volatile boolean pdfBoxInitialized = false;

    private static synchronized void ensurePdfBoxInitialized(Context ctx) {
        if (!pdfBoxInitialized) {
            PDFBoxResourceLoader.init(ctx.getApplicationContext());
            pdfBoxInitialized = true;
        }
    }

    /** تهيئة مبكرة اختيارية (تُستدعى من ClinicalMasterApp في الخلفية عند
     *  إقلاع التطبيق) عشان أول استخراج PDF فعلي ما ينتظرش تحميل موارد
     *  PdfBox لحظتها. آمنة للاستدعاء أكتر من مرة (نفس فحص pdfBoxInitialized). */
    static void warmUp(Context ctx) {
        try {
            ensurePdfBoxInitialized(ctx);
        } catch (Throwable ignored) {
        }
    }

    /** يستخرج نصًا من الملف بناءً على امتداده. يرجع null لو الامتداد غير
     *  مدعوم، أو الملف غير موجود/فارغ، أو حصل أي خطأ أثناء الاستخراج. */
    static String extractText(Context ctx, File file, String extension) {
        if (file == null || !file.exists() || file.length() == 0 || extension == null) return null;
        String ext = extension.toLowerCase(Locale.ROOT);
        try {
            switch (ext) {
                case "pdf":
                    return extractPdf(ctx, file);
                case "txt":
                case "md":
                    return extractPlainText(file);
                default:
                    return null;
            }
        } catch (Throwable t) {
            // أي استثناء (IOException، أو استثناء داخلي من PdfBox لملف تالف/محمي
            // بكلمة سر) - نتعامل معاه كملف تعذّر استخراجه، مش كخطأ يوقف التطبيق.
            return null;
        }
    }

    private static String extractPdf(Context ctx, File file) throws IOException {
        ensurePdfBoxInitialized(ctx);
        // المسار الأدق: نفس مستخرج القارئ الصوتي (مواضع الأحرف الفعلية، ترتيب القراءة عربيًا من اليمين، أعمدة وجداول،
        // حذف الرأس/التذييل، التعرّف الضوئي للصفحات الممسوحة أو ذات الحروف التالفة). صفحة بصفحة حتى نصل حدّ الطول.
        String accurate = null;
        try {
            accurate = extractPdfAccurate(ctx, file);
        } catch (Throwable ignored) {
        }
        if (accurate != null) return truncate(accurate);
        // احتياطي: المستخرج العادي لكن بترتيب المواضع، مع تنظيف أشكال العرض العربية والأحرف الخفية
        try (PDDocument doc = PDDocument.load(file)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return truncate(cleanExtracted(stripper.getText(doc)));
        }
    }

    private static String extractPdfAccurate(Context ctx, File file) throws IOException {
        try (PdfSpeechText.Source src = PdfSpeechText.Source.open(ctx, file)) {
            int pages = src.pageCount();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < pages && sb.length() < MAX_EXTRACTED_CHARS; i++) {
                PdfSpeechText.PageText pt = src.page(i, true);
                if (pt == null || pt.text == null || pt.text.trim().isEmpty()) continue;
                if (sb.length() > 0) sb.append("\n\n");
                sb.append(pt.text.trim());
            }
            return sb.length() == 0 ? null : sb.toString();
        }
    }

    /** أشكال العرض العربية (ﻻ ﻣ ﷲ) -> حروفها الأصلية، وحذف التطويل والأحرف الخفية، وضبط الفراغات. */
    private static String cleanExtracted(String raw) {
        if (raw == null) return null;
        String t = raw;
        boolean presentation = false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if ((c >= 0xFB50 && c <= 0xFDFF) || (c >= 0xFE70 && c <= 0xFEFF) || (c >= 0xFB00 && c <= 0xFB06)) {
                presentation = true;
                break;
            }
        }
        if (presentation) t = java.text.Normalizer.normalize(t, java.text.Normalizer.Form.NFKC);
        StringBuilder sb = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if ((c >= 0x200B && c <= 0x200F) || (c >= 0x202A && c <= 0x202E) || (c >= 0x2066 && c <= 0x2069)
                    || c == 0xFEFF || c == 0x00AD || c == 0x0640 || (c >= 0xE000 && c <= 0xF8FF)) {
                continue;
            }
            sb.append(c);
        }
        return sb.toString().replaceAll("[ \\t]+", " ").replaceAll("\\n{3,}", "\n\n");
    }

    private static String extractPlainText(File file) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (Reader r = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            char[] buf = new char[4096];
            int n;
            while (sb.length() < MAX_EXTRACTED_CHARS && (n = r.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
        }
        return truncate(sb.toString());
    }

    private static String truncate(String text) {
        if (text == null) return null;
        String t = text.trim();
        if (t.isEmpty()) return null;
        return t.length() > MAX_EXTRACTED_CHARS ? t.substring(0, MAX_EXTRACTED_CHARS) : t;
    }
}
